package io.github.asutorufa.yuhaiin.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import io.github.asutorufa.yuhaiin.BuildConfig
import io.github.asutorufa.yuhaiin.IYuhaiinVpnBinder
import io.github.asutorufa.yuhaiin.MainActivity
import io.github.asutorufa.yuhaiin.R
import io.github.asutorufa.yuhaiin.database.Manager
import io.github.asutorufa.yuhaiin.database.Profile
import io.github.asutorufa.yuhaiin.util.Routes
import yuhaiin.*
import java.net.InetSocketAddress


class YuhaiinVpnService : VpnService() {
    companion object {
        private const val INTENT_PREFIX = "YUHAIIN"
        const val INTENT_DISCONNECTED = INTENT_PREFIX + "DISCONNECTED"
        const val INTENT_CONNECTED = INTENT_PREFIX + "CONNECTED"
        const val INTENT_CONNECTING = INTENT_PREFIX + "CONNECTING"
        const val INTENT_DISCONNECTING = INTENT_PREFIX + "DISCONNECTING"
        const val INTENT_ERROR = INTENT_PREFIX + "ERROR"

        enum class State {
            CONNECTED,
            CONNECTING,
            DISCONNECTING,
            DISCONNECTED,
        }

        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
    }

    private val mBinder = VpnBinder()
    private val tag = this.javaClass.simpleName
    private var state = State.DISCONNECTED
    private var mInterface: ParcelFileDescriptor? = null
//    private var yuhaiin: Process? = null
    private val app = App()


    inner class VpnBinder : IYuhaiinVpnBinder.Stub() {
        override fun isRunning() = state == State.CONNECTED
        override fun stop() = this@YuhaiinVpnService.onRevoke()
        override fun saveNewBypass(url: String): String {
            Log.d(tag, "SaveNewBypass: $url")
            return try {
                app.saveNewBypass(
                    Uri.parse(url).toString(),
                    getExternalFilesDir("yuhaiin")!!.absolutePath
                )
                ""
            } catch (e: Exception) {
                e.message.toString()
            }
        }
    }

    //    private val connectivityManager: ConnectivityManager by lazy {
//        getSystemService(
//            CONNECTIVITY_SERVICE
//        ) as ConnectivityManager
//    }
//    private val defaultNetworkRequest by lazy {
//        NetworkRequest.Builder()
//            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
//            .build()
//    }
//    private val defaultNetworkCallback: NetworkCallback = object : NetworkCallback() {
//        override fun onAvailable(network: Network) {
//            setUnderlyingNetworks(arrayOf(network))
//        }
//
//        override fun onCapabilitiesChanged(
//            network: Network,
//            networkCapabilities: NetworkCapabilities
//        ) {
//            setUnderlyingNetworks(arrayOf(network))
//        }
//
//        override fun onLost(network: Network) {
//            setUnderlyingNetworks(null)
//        }
//    }

    override fun onBind(intent: Intent?) =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else mBinder
//
//    private fun getPid(p: Process): Int =
//        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)

    private fun stop() {
        if (state != State.CONNECTED) return
        setState(State.DISCONNECTING)

        try {
            stopForeground(true)
            mInterface?.close()
//            if (yuhaiin != null) {
//                //  Runtime.getRuntime().exec("killall libyuhaiin.so")
//                //  Os.kill(getPid(yuhaiin!!), OsConstants.SIGTERM)
//                yuhaiin!!.destroy()
//                while (yuhaiin != null || state != State.DISCONNECTED) {
//                    Log.d(tag, "stop: waiting for yuhaiin stopped")
//                    Thread.sleep(100)
//                }
//            }

            app.stop()
            setState(State.DISCONNECTED)

//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
//            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    override fun onRevoke() {
        Log.d(tag, "onRevoke")
        stop()
        super.onRevoke()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "starting")
        if (state == State.CONNECTED || state == State.CONNECTING || app.running()) {
            Log.d(tag, "already running")
            return START_STICKY
        }

        setState(State.CONNECTING)

        try {
            val profile = Manager.db.getProfileByName(Manager.db.getLastProfile() ?: "Default")

            configure(profile)
            start(profile)
            startNotification(profile.name)

            setState(State.CONNECTED)

        } catch (e: Exception) {
            e.printStackTrace()
            setState(State.DISCONNECTED)
            applicationContext.sendBroadcast(Intent(INTENT_ERROR).also {
                it.putExtra(
                    "message",
                    e.toString()
                )
            })
            onRevoke()
        }

        return START_STICKY
    }

    private fun configure(profile: Profile) {
        val b = Builder()
        b.setMtu(VPN_MTU)
            .setSession(profile.name)
            .addAddress(PRIVATE_VLAN4_CLIENT, 24)
            .addDnsServer(PRIVATE_VLAN4_ROUTER)

        if (profile.hasIPv6) {
            // Route all IPv6 traffic
            b.addAddress(PRIVATE_VLAN6_CLIENT, 126)
                .addRoute("::", 0)
        }

        Routes.addRoutes(this, b, profile.route)
        Routes.addRoute(b, profile.fakeDnsCidr)

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//            connectivityManager.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
//        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false)
            if (profile.httpServerPort != 0 && profile.appendHttpProxyToSystem) {
                b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", profile.httpServerPort))
            }
        }

        Log.d(tag, "configure: ${profile.appList}")

        when (profile.isPerApp) {
            true -> {
                fun process(app: String) =
                    try {
                        if (profile.isBypassApp) b.addDisallowedApplication(app.trim()) else b.addAllowedApplication(
                            app.trim()
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }


                if (profile.isBypassApp) b.addDisallowedApplication(BuildConfig.APPLICATION_ID)
                profile.appList.map {
                    if ((!profile.isBypassApp && it == BuildConfig.APPLICATION_ID) || it.isEmpty()) return
                    process(it)
                }
            }

            false -> {
                // Just bypass myself
                try {
                    b.addDisallowedApplication(BuildConfig.APPLICATION_ID)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        mInterface = b.establish()
    }

    private fun start(profile: Profile) {
        Log.d(tag, "start yuhaiin: $profile")
        if (profile.yuhaiinPort <= 0) throw Exception("Invalid yuhaiin port: ${profile.yuhaiinPort}")
        var address = "127.0.0.1"
        if (profile.allowLan) address = "0.0.0.0"
        app.start(Opts().apply {
            host = "${address}:${profile.yuhaiinPort}"
            savepath = getExternalFilesDir("yuhaiin")!!.absolutePath

            if (profile.socks5ServerPort > 0) socks5 = "${address}:${profile.socks5ServerPort}"
            if (profile.httpServerPort > 0) http = "${address}:${profile.httpServerPort}"

            saveLogcat = profile.saveLogcat
            bypass = Bypass().apply {
                block = profile.ruleBlock
                proxy = profile.ruleProxy
                direct = profile.ruleDirect
                tcp = profile.bypass.tcp
                udp = profile.bypass.udp
            }

            dns = DNSSetting().apply {
                if (profile.dnsPort > 0) server = "${address}:${profile.dnsPort}"
                fakedns = profile.fakeDnsCidr.isNotEmpty()
                fakednsIpRange = profile.fakeDnsCidr
                remote = DNS().apply {
                    host = profile.remoteDns.host
                    subnet = profile.remoteDns.subnet
                    type = profile.remoteDns.type
                    tlsServername = profile.remoteDns.tlsServerName
                    proxy = profile.remoteDns.proxy
                }
                local = DNS().apply {
                    host = profile.localDns.host
                    subnet = profile.localDns.subnet
                    type = profile.localDns.type
                    tlsServername = profile.localDns.tlsServerName
                    proxy = profile.localDns.proxy
                }
                bootstrap = DNS().apply {
                    host = profile.bootstrapDns.host
                    subnet = profile.bootstrapDns.subnet
                    type = profile.bootstrapDns.type
                    tlsServername = profile.bootstrapDns.tlsServerName
                    proxy = profile.bootstrapDns.proxy
                }
                tun = TUN().apply {
                    fd = mInterface!!.fd
                    mtu = VPN_MTU
                    gateway = PRIVATE_VLAN4_ROUTER
                    dnsHijacking = profile.dnsHijacking
                    // 0: fdbased, 1: channel
                    driver = 0
                    uidDumper = UidDumper(applicationContext)
                }
            }
        })
    }


    class UidDumper(context: Context) : yuhaiin.UidDumper {
        private var connectivity: ConnectivityManager
        private var packageManager: PackageManager

        init {
            connectivity = context.getSystemService()!!
            packageManager = context.packageManager
        }

        override fun dumpUid(
            p0: Int,
            srcIp: String?,
            srcPort: Int,
            destIp: String?,
            destPort: Int
        ): Int {
            return connectivity.getConnectionOwnerUid(
                p0,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(destIp, destPort)
            )
        }

        override fun getUidInfo(p0: Int): String {
            return packageManager.getNameForUid(p0) ?: "unknown"
        }
    }
    /*
    @OptIn(DelicateCoroutinesApi::class)
    private fun start(profile: Profile) {
        Log.d(tag, "start yuhaiin: $profile")
        if (profile.yuhaiinPort <= 0) throw Exception("Invalid yuhaiin port: ${profile.yuhaiinPort}")


        val opts = JsonObject().apply {
            var address = "127.0.0.1"
            if (profile.allowLan) address = "0.0.0.0"
            addProperty("host", getAddress(address, profile.yuhaiinPort))
            addProperty("savepath", getExternalFilesDir("yuhaiin")!!.absolutePath)
            addProperty("socks5", getAddress(address, profile.socks5ServerPort))
            addProperty("http", getAddress(address, profile.httpServerPort))
            addProperty("save_logcat", profile.saveLogcat)
            addProperty("block", profile.ruleBlock)
            add("dns", JsonObject().apply {
                addProperty("server", getAddress(address, profile.dnsPort))
                addProperty("fakedns", profile.fakeDnsCidr.isNotEmpty())
                addProperty("fakedns_ip_range", profile.fakeDnsCidr)
                add("remote", JsonObject().apply {
                    addProperty("host", profile.remoteDns.host)
                    addProperty("type", profile.remoteDns.type)
                    addProperty("subnet", profile.remoteDns.subnet)
                    addProperty("proxy", profile.remoteDns.proxy)
                    addProperty("tls_servername", profile.remoteDns.tlsServerName)
                })
                add("local", JsonObject().apply {
                    addProperty("host", profile.localDns.host)
                    addProperty("type", profile.localDns.type)
                    addProperty("subnet", profile.localDns.subnet)
                    addProperty("proxy", profile.localDns.proxy)
                    addProperty("tls_servername", profile.localDns.tlsServerName)
                })
                add("bootstrap", JsonObject().apply {
                    addProperty("host", profile.bootstrapDns.host)
                    addProperty("type", profile.bootstrapDns.type)
                    addProperty("subnet", profile.bootstrapDns.subnet)
                    addProperty("proxy", profile.bootstrapDns.proxy)
                    addProperty("tls_servername", profile.bootstrapDns.tlsServerName)
                })
            })
            add("tun", JsonObject().apply {
                addProperty("fd", 0)
                addProperty("mtu", VPN_MTU)
                addProperty("gateway", PRIVATE_VLAN4_ROUTER)
                addProperty("dns_hijacking", profile.dnsHijacking)
            })
        }

        val socksPath = File(applicationInfo.dataDir + "/sock_path").absolutePath

        GlobalScope.launch(Dispatchers.IO) {
            try {
//                val optBase64 = Base64.encodeToString(opts.toString().toByteArray(), Base64.NO_WRAP)
//                Log.d(tag, "start: $optBase64")

                val command = buildList<String> {
                    add("${applicationInfo.nativeLibraryDir}/libyuhaiin.so")
                    add(opts.toString())
                    add(socksPath)
                }

                Log.d(tag, "start: $command")

                yuhaiin = ProcessBuilder(command).start()

                launch {
                    Scanner(yuhaiin!!.errorStream).apply {
                        while (hasNextLine()) {
                            println(nextLine())
                        }
                        close()
                    }
                }
//                launch {
                Scanner(yuhaiin!!.inputStream).apply {
                    while (hasNextLine()) println(nextLine())
                    close()
                }
//                }

                yuhaiin!!.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            yuhaiin = null
            onRevoke()
            setState(State.DISCONNECTED)
        }
        sendFd(socksPath)
    }


    private fun getAddress(hostname: String, port: Int): String {
        if (port <= 0) {
            return ""
        }

        return "${hostname}:${port}"
    }

    private fun sendFd(path: String) {
        val fd = mInterface!!.fileDescriptor

        var tries = 0
        while (true) try {
            Thread.sleep(50L shl tries)
            Log.d(packageName, "sendFd tries: $tries, $path")
            LocalSocket().use { localSocket ->
                localSocket.connect(
                    LocalSocketAddress(
                        path,
                        LocalSocketAddress.Namespace.FILESYSTEM
                    )
                )
                localSocket.setFileDescriptorsForSend(arrayOf(fd))
                localSocket.outputStream.write(42)
            }
            break
        } catch (e: Exception) {
            Log.d(packageName, e.toString())
            if (tries > 5) throw e
            tries += 1
        }
    }

    */

    private fun startNotification(name: String) {
        // Notifications on Oreo and above need a channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                packageName,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            notificationManager.createNotificationChannel(channel)
        }

        startForeground(
            1,
            NotificationCompat.Builder(this, packageName)
                .setContentTitle("yuhaiin running")
                .setContentText(String.format(getString(R.string.notify_msg), name))
                .setSmallIcon(R.drawable.emoji_nature)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
        )
    }

    private fun setState(state: State) {
        this.state = state

        when (state) {
            State.CONNECTED -> {
                applicationContext.sendBroadcast(Intent(INTENT_CONNECTED))
            }
            State.DISCONNECTED -> {
                applicationContext.sendBroadcast(Intent(INTENT_DISCONNECTED))
            }
            State.DISCONNECTING -> {
                applicationContext.sendBroadcast(Intent(INTENT_DISCONNECTING))
            }
            State.CONNECTING -> {
                applicationContext.sendBroadcast(Intent(INTENT_CONNECTING))
            }
        }
    }
}
