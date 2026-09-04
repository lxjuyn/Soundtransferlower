package com.example.soundtransferlower;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class PendingMessagesFragment extends Fragment {
    private RecyclerView recyclerView;
    private PendingAdapter adapter;
    private PendingMessageManager manager;

    // 独立接口，避免内部静态问题
    public interface OnItemClickListener {
        void onItemClick(PendingMessage msg);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pending_messages, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));

        manager = PendingMessageManager.getInstance(getActivity());
        adapter = new PendingAdapter(manager.getAllMessages());
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(PendingMessage msg) {
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).sendPendingMessage(msg);
                } else {
                    Toast.makeText(getActivity(), "无法发送，请返回主界面", Toast.LENGTH_SHORT).show();
                }
            }
        });
        recyclerView.setAdapter(adapter);
        Md3Ui.applyTree(view);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    public void refreshList() {
        if (adapter != null) {
            adapter.updateData(manager.getAllMessages());
        }
    }

    // 内部适配器改为静态类
    private static class PendingAdapter extends RecyclerView.Adapter<PendingAdapter.ViewHolder> {
        private List<PendingMessage> data;
        private OnItemClickListener listener; // 使用外部接口

        public PendingAdapter(List<PendingMessage> data) {
            this.data = data;
        }

        public void setOnItemClickListener(OnItemClickListener listener) {
            this.listener = listener;
        }

        public void updateData(List<PendingMessage> newData) {
            this.data = newData;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pending_message, parent, false);
            Md3Ui.applyTree(v);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            PendingMessage msg = data.get(position);
            holder.tvTarget.setText("目标: " + msg.targetDeviceName);
            holder.tvContent.setText("内容: " + msg.content);
            holder.tvReason.setText("原因: " + msg.reason);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(msg);
            });
        }

        @Override
        public int getItemCount() {
            return data == null ? 0 : data.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTarget, tvContent, tvReason;
            public ViewHolder(View itemView) {
                super(itemView);
                tvTarget = itemView.findViewById(R.id.tvTarget);
                tvContent = itemView.findViewById(R.id.tvContent);
                tvReason = itemView.findViewById(R.id.tvReason);
            }
        }
    }
}