package com.example.soundtransferlower;

import java.io.File;
import java.util.Date;

public class Message {
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;
    public static final int TYPE_VOICE = 3;

    private String content;
    private boolean isSent;
    private Date timestamp;
    private int type;
    private String filePath;
    private String fileName;
    private long fileSize;
    private int voiceDuration;

    // 文本构造
    public Message(String content, boolean isSent, Date timestamp) {
        this.content = content;
        this.isSent = isSent;
        this.timestamp = timestamp;
        this.type = TYPE_TEXT;
        this.filePath = null;
        this.fileName = null;
        this.fileSize = 0;
        this.voiceDuration = 0;
    }

    // 图片/文件构造
    public Message(String content, boolean isSent, Date timestamp, String filePath) {
        this.content = content;
        this.isSent = isSent;
        this.timestamp = timestamp;
        this.filePath = filePath;
        if (filePath != null && isImageFile(filePath)) {
            this.type = TYPE_IMAGE;
        } else {
            this.type = TYPE_FILE;
        }
        this.fileName = new File(filePath).getName();
        this.fileSize = new File(filePath).length();
        this.voiceDuration = 0;
    }

    // 文件构造（指定文件名和大小）
    public Message(String content, boolean isSent, Date timestamp, String filePath, String fileName, long fileSize) {
        this.content = content;
        this.isSent = isSent;
        this.timestamp = timestamp;
        this.filePath = filePath;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.voiceDuration = 0;
        if (filePath != null && isImageFile(filePath)) {
            this.type = TYPE_IMAGE;
        } else {
            this.type = TYPE_FILE;
        }
    }

    // 语音构造
    // 语音构造器
    public Message(boolean isSent, Date timestamp, String filePath, int durationSeconds) {
        this.content = "语音";
        this.isSent = isSent;
        this.timestamp = timestamp;
        this.type = TYPE_VOICE;
        this.filePath = filePath;
        this.fileName = new File(filePath).getName();
        this.fileSize = new File(filePath).length();
        this.voiceDuration = durationSeconds;
    }
    private boolean isImageFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    // Getters
    public String getContent() { return content; }
    public boolean isSent() { return isSent; }
    public Date getTimestamp() { return timestamp; }
    public int getType() { return type; }
    public String getFilePath() { return filePath; }
    public String getFileName() { return fileName != null ? fileName : (filePath != null ? new File(filePath).getName() : ""); }
    public long getFileSize() { return fileSize > 0 ? fileSize : (filePath != null ? new File(filePath).length() : 0); }
    public int getVoiceDuration() { return voiceDuration; }

    // Setters
    public void setContent(String content) { this.content = content; }
    public void setSent(boolean sent) { isSent = sent; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public void setVoiceDuration(int duration) { this.voiceDuration = duration; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Message message = (Message) obj;
        if (isSent != message.isSent) return false;
        if (type != message.type) return false;
        if (fileSize != message.fileSize) return false;
        if (voiceDuration != message.voiceDuration) return false;
        if (content != null ? !content.equals(message.content) : message.content != null) return false;
        if (timestamp != null ? !timestamp.equals(message.timestamp) : message.timestamp != null) return false;
        if (filePath != null ? !filePath.equals(message.filePath) : message.filePath != null) return false;
        return fileName != null ? fileName.equals(message.fileName) : message.fileName == null;
    }

    @Override
    public int hashCode() {
        int result = content != null ? content.hashCode() : 0;
        result = 31 * result + (isSent ? 1 : 0);
        result = 31 * result + (timestamp != null ? timestamp.hashCode() : 0);
        result = 31 * result + type;
        result = 31 * result + (filePath != null ? filePath.hashCode() : 0);
        result = 31 * result + (fileName != null ? fileName.hashCode() : 0);
        result = 31 * result + (int) (fileSize ^ (fileSize >>> 32));
        result = 31 * result + voiceDuration;
        return result;
    }
}