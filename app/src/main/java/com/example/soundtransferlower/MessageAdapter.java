package com.example.soundtransferlower;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {
    private List<Message> messages;
    private SimpleDateFormat timeFormat;
    private OnMessageLongClickListener longClickListener;
    private OnMessageClickListener clickListener;

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message, int position);
    }

    public interface OnMessageClickListener {
        void onMessageClick(Message message, int position);
    }

    public MessageAdapter(List<Message> messages) {
        this.messages = messages;
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    }

    public void setOnMessageLongClickListener(OnMessageLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnMessageClickListener(OnMessageClickListener listener) {
        this.clickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = messages.get(position);
        int base;
        if (msg.getType() == Message.TYPE_TEXT) {
            base = 0;
        } else if (msg.getType() == Message.TYPE_IMAGE) {
            base = 2;
        } else { // TYPE_FILE
            base = 4;
        }
        return base + (msg.isSent() ? 0 : 1);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layoutRes;
        switch (viewType) {
            case 0: // 发送文本
                layoutRes = R.layout.item_message_sent;
                break;
            case 1: // 接收文本
                layoutRes = R.layout.item_message_received;
                break;
            case 2: // 发送图片
                layoutRes = R.layout.item_message_sent_image;
                break;
            case 3: // 接收图片
                layoutRes = R.layout.item_message_received_image;
                break;
            case 4: // 发送文件
                layoutRes = R.layout.item_message_sent_file;
                break;
            case 5: // 接收文件
                layoutRes = R.layout.item_message_received_file;
                break;
            default:
                layoutRes = R.layout.item_message_sent;
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view, viewType);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Message message = messages.get(position);
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onMessageLongClick(message, position);
            }
            return true;
        });
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onMessageClick(message, position);
            }
        });

        // 设置时间
        if (message.getTimestamp() != null) {
            holder.tvTime.setText(timeFormat.format(message.getTimestamp()));
        }

        int type = message.getType();
        if (type == Message.TYPE_TEXT) {
            // 文本消息
            holder.tvMessage.setText(message.getContent());
            holder.tvMessage.setVisibility(View.VISIBLE);
            if (holder.ivImage != null) holder.ivImage.setVisibility(View.GONE);
            if (holder.tvFileName != null) holder.tvFileName.setVisibility(View.GONE);
            if (holder.tvFileSize != null) holder.tvFileSize.setVisibility(View.GONE);
        } else if (type == Message.TYPE_IMAGE) {
            // 图片消息：显示缩略图
            if (holder.tvMessage != null) holder.tvMessage.setVisibility(View.GONE);
            if (holder.tvFileName != null) holder.tvFileName.setVisibility(View.GONE);
            if (holder.tvFileSize != null) holder.tvFileSize.setVisibility(View.GONE);
            if (holder.ivImage != null) {
                holder.ivImage.setVisibility(View.VISIBLE);
                loadImageThumbnail(holder.ivImage, message.getFilePath());
            }
        } else { // TYPE_FILE
            // 文件消息：显示文件名和大小
            if (holder.tvMessage != null) holder.tvMessage.setVisibility(View.GONE);
            if (holder.ivImage != null) holder.ivImage.setVisibility(View.GONE);
            if (holder.tvFileName != null) {
                holder.tvFileName.setVisibility(View.VISIBLE);
                holder.tvFileName.setText(message.getFileName());
            }
            if (holder.tvFileSize != null) {
                holder.tvFileSize.setVisibility(View.VISIBLE);
                holder.tvFileSize.setText(formatFileSize(message.getFileSize()));
            }
        }

        // 发送者标签（仅接收消息）
        if (holder.tvSender != null) {
            holder.tvSender.setText(message.isSent() ? "我" : "对方");
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void loadImageThumbnail(ImageView imageView, String filePath) {
        new ThumbnailTask(imageView).execute(filePath);
    }

    private static class ThumbnailTask extends AsyncTask<String, Void, Bitmap> {
        private WeakReference<ImageView> imageViewRef;

        ThumbnailTask(ImageView imageView) {
            imageViewRef = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            String path = params[0];
            if (path == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int sampleSize = Math.max(options.outWidth / 100, options.outHeight / 100);
            sampleSize = Math.max(1, sampleSize);
            options.inSampleSize = sampleSize;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView iv = imageViewRef.get();
            if (iv != null && bitmap != null) {
                iv.setImageBitmap(bitmap);
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        TextView tvTime;
        TextView tvSender;
        ImageView ivImage;
        TextView tvFileName;
        TextView tvFileSize;

        public ViewHolder(View itemView, int viewType) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvSender = itemView.findViewById(R.id.tvSender);
            ivImage = itemView.findViewById(R.id.ivImage);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
        }
    }
}