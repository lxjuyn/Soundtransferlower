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
    private OnVoiceClickListener voiceClickListener;

    public interface OnMessageLongClickListener {
        void onMessageLongClick(Message message, int position);
    }

    public interface OnMessageClickListener {
        void onMessageClick(Message message, int position);
    }

    public interface OnVoiceClickListener {
        void onVoiceClick(Message message, int position);
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

    public void setOnVoiceClickListener(OnVoiceClickListener listener) {
        this.voiceClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = messages.get(position);
        int type = msg.getType();
        boolean sent = msg.isSent();
        if (type == Message.TYPE_TEXT) {
            return sent ? 0 : 1;
        } else if (type == Message.TYPE_IMAGE) {
            return sent ? 2 : 3;
        } else if (type == Message.TYPE_FILE) {
            return sent ? 4 : 5;
        } else if (type == Message.TYPE_VOICE) {
            return sent ? 6 : 7;
        }
        return sent ? 0 : 1;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layoutRes;
        switch (viewType) {
            case 0: layoutRes = R.layout.item_message_sent; break;
            case 1: layoutRes = R.layout.item_message_received; break;
            case 2: layoutRes = R.layout.item_message_sent_image; break;
            case 3: layoutRes = R.layout.item_message_received_image; break;
            case 4: layoutRes = R.layout.item_message_sent_file; break;
            case 5: layoutRes = R.layout.item_message_received_file; break;
            case 6: layoutRes = R.layout.item_message_sent_voice; break;
            case 7: layoutRes = R.layout.item_message_received_voice; break;
            default: layoutRes = R.layout.item_message_sent;
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view, viewType);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Message message = messages.get(position);
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onMessageLongClick(message, position);
            return true;
        });
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onMessageClick(message, position);
        });

        if (message.getTimestamp() != null) {
            holder.tvTime.setText(timeFormat.format(message.getTimestamp()));
        }

        int type = message.getType();

        // 先隐藏所有非通用视图（防御性编程）
        if (holder.tvMessage != null) holder.tvMessage.setVisibility(View.GONE);
        if (holder.ivImage != null) holder.ivImage.setVisibility(View.GONE);
        if (holder.tvFileName != null) holder.tvFileName.setVisibility(View.GONE);
        if (holder.tvFileSize != null) holder.tvFileSize.setVisibility(View.GONE);
        if (holder.ivVoiceIcon != null) holder.ivVoiceIcon.setVisibility(View.GONE);
        if (holder.tvVoiceDuration != null) holder.tvVoiceDuration.setVisibility(View.GONE);

        if (type == Message.TYPE_TEXT) {
            if (holder.tvMessage != null) {
                holder.tvMessage.setVisibility(View.VISIBLE);
                holder.tvMessage.setText(message.getContent());
            }
        } else if (type == Message.TYPE_IMAGE) {
            if (holder.ivImage != null) {
                holder.ivImage.setVisibility(View.VISIBLE);
                loadImageThumbnail(holder.ivImage, message.getFilePath());
            }
        } else if (type == Message.TYPE_FILE) {
            if (holder.tvFileName != null) {
                holder.tvFileName.setVisibility(View.VISIBLE);
                holder.tvFileName.setText(message.getFileName());
            }
            if (holder.tvFileSize != null) {
                holder.tvFileSize.setVisibility(View.VISIBLE);
                holder.tvFileSize.setText(formatFileSize(message.getFileSize()));
            }
        } else if (type == Message.TYPE_VOICE) {
            if (holder.ivVoiceIcon != null) {
                holder.ivVoiceIcon.setVisibility(View.VISIBLE);
                holder.ivVoiceIcon.setImageResource(R.drawable.ic_voice);
               // holder.ivVoiceIcon.setOnClickListener(v -> {
             //       if (voiceClickListener != null) voiceClickListener.onVoiceClick(message, position);
              //  });
            }
            if (holder.tvVoiceDuration != null) {
                holder.tvVoiceDuration.setVisibility(View.VISIBLE);
                holder.tvVoiceDuration.setText(message.getVoiceDuration() + "\"");
            }
        }

        if (holder.tvSender != null) {
            holder.tvSender.setText(message.isSent() ? "我" : "对方");
        }
    }

    private void loadImageThumbnail(ImageView imageView, String filePath) {
        new ThumbnailTask(imageView).execute(filePath);
    }

    private static class ThumbnailTask extends AsyncTask<String, Void, Bitmap> {
        private WeakReference<ImageView> imageViewRef;
        ThumbnailTask(ImageView imageView) { imageViewRef = new WeakReference<>(imageView); }
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
            if (iv != null && bitmap != null) iv.setImageBitmap(bitmap);
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTime, tvSender, tvFileName, tvFileSize, tvVoiceDuration;
        ImageView ivImage, ivVoiceIcon;

        public ViewHolder(View itemView, int viewType) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvSender = itemView.findViewById(R.id.tvSender);
            ivImage = itemView.findViewById(R.id.ivImage);
            tvFileName = itemView.findViewById(R.id.tvFileName);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
            ivVoiceIcon = itemView.findViewById(R.id.ivVoiceIcon);
            tvVoiceDuration = itemView.findViewById(R.id.tvVoiceDuration);
        }
    }
}