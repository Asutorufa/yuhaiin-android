package com.takisoft.preferencex.widget;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import com.takisoft.preferencex.simplemenu.R;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class SimpleMenuListAdapter extends RecyclerView.Adapter<SimpleMenuListItemHolder> {

    private final SimpleMenuPopupWindow mWindow;

    public SimpleMenuListAdapter(SimpleMenuPopupWindow window) {
        super();

        mWindow = window;
    }

    @NonNull
    @Override
    public SimpleMenuListItemHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new SimpleMenuListItemHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.simple_menu_item, parent, false));
    }

    @Override
    public void onBindViewHolder(final SimpleMenuListItemHolder holder, int position) {
        holder.bind(mWindow, position);
    }

    @Override
    public int getItemCount() {
        return mWindow.getEntries() == null ? 0 : mWindow.getEntries().length;
    }
}
