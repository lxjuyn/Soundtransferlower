package com.example.soundtransferlower;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Scoped Storage 兼容辅助类
 * 封装版本兼容的文件路径获取和文件操作
 */
public class FileHelper {
    private static final String TAG = "FileHelper";

    /**
     * 判断是否使用 Scoped Storage (API 29+)
     */
    public static boolean isScopedStorage() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /**
     * 获取聊天记录存储目录
     * API 29+: 使用 context.getExternalFilesDir("chat")
     * API < 29: 使用 context.getExternalFilesDir("chat")
     */
    public static File getChatDir(Context context) {
        File chatDir = context.getExternalFilesDir("chat");
        if (chatDir != null && !chatDir.exists()) {
            chatDir.mkdirs();
        }
        return chatDir;
    }

    /**
     * 获取下载目录
     * API 29+: 使用 MediaStore API 写入 Downloads
     * API < 29: 使用 Environment.getExternalStoragePublicDirectory()
     */
    public static File getDownloadDir() {
        if (isScopedStorage()) {
            // API 29+ 需要使用 MediaStore，这里返回一个临时目录
            // 实际写入时应使用 MediaStore API
            File tempDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SoundTransfer");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            return tempDir;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
    }

    /**
     * 获取 DCIM 目录（用于保存图片）
     * API 29+: 使用 MediaStore API 写入 DCIM
     * API < 29: 使用 Environment.getExternalStoragePublicDirectory()
     */
    public static File getDCIMDir() {
        if (isScopedStorage()) {
            File tempDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "SoundTransfer");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            return tempDir;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
    }

    /**
     * 获取文件存储目录（用于接收的文件）
     * API 29+: 使用 context.getExternalFilesDir("files")
     * API < 29: 使用 context.getExternalFilesDir("files")
     */
    public static File getFileStorageDir(Context context) {
        File filesDir = context.getExternalFilesDir("files");
        if (filesDir != null && !filesDir.exists()) {
            filesDir.mkdirs();
        }
        return filesDir;
    }

    /**
     * 获取语音存储目录
     * API 29+: 使用 context.getExternalFilesDir("voices")
     * API < 29: 使用 context.getExternalFilesDir("voices")
     */
    public static File getVoiceStorageDir(Context context) {
        File voiceDir = context.getExternalFilesDir("voices");
        if (voiceDir != null && !voiceDir.exists()) {
            voiceDir.mkdirs();
        }
        return voiceDir;
    }

    /**
     * 通过 MediaStore 保存文件到 Downloads 目录 (API 29+)
     *
     * @param context    上下文
     * @param fileName   文件名
     * @param inputStream 文件输入流
     * @param mimeType   MIME 类型
     * @return 保存后的 Uri，失败返回 null
     */
    public static Uri saveToDownloadsViaMediaStore(Context context, String fileName,
                                                   InputStream inputStream, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "saveToDownloadsViaMediaStore 仅支持 API 29+");
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SoundTransfer");

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.e(TAG, "MediaStore 插入失败");
            return null;
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                resolver.delete(uri, null, null);
                return null;
            }

            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.flush();
            return uri;
        } catch (IOException e) {
            Log.e(TAG, "写入 MediaStore 失败", e);
            resolver.delete(uri, null, null);
            return null;
        }
    }

    /**
     * 通过 MediaStore 保存图片到 DCIM 目录 (API 29+)
     *
     * @param context    上下文
     * @param fileName   文件名
     * @param inputStream 文件输入流
     * @param mimeType   MIME 类型
     * @return 保存后的 Uri，失败返回 null
     */
    public static Uri saveToDCIMViaMediaStore(Context context, String fileName,
                                              InputStream inputStream, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "saveToDCIMViaMediaStore 仅支持 API 29+");
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SoundTransfer");

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.e(TAG, "MediaStore 插入失败");
            return null;
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                resolver.delete(uri, null, null);
                return null;
            }

            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            outputStream.flush();
            return uri;
        } catch (IOException e) {
            Log.e(TAG, "写入 MediaStore 失败", e);
            resolver.delete(uri, null, null);
            return null;
        }
    }

    /**
     * 生成唯一的文件名（避免重名）
     *
     * @param dir         目标目录
     * @param originalName 原始文件名
     * @return 唯一的文件名
     */
    public static String generateUniqueFileName(File dir, String originalName) {
        File destFile = new File(dir, originalName);
        int count = 1;
        while (destFile.exists()) {
            String name = originalName;
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex > 0) {
                name = originalName.substring(0, dotIndex) + "_" + count + originalName.substring(dotIndex);
            } else {
                name = originalName + "_" + count;
            }
            destFile = new File(dir, name);
            count++;
        }
        return destFile.getName();
    }

    /**
     * 获取文件的 MIME 类型
     *
     * @param filePath 文件路径
     * @return MIME 类型
     */
    public static String getMimeType(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".opus")) return "audio/opus";
        return "application/octet-stream";
    }

    /**
     * 通过 SAF 创建文档 Uri
     * 需要在 Activity 中调用 startActivityForResult
     *
     * @param fileName 文件名
     * @return Intent 用于 startActivityForResult
     */
    public static android.content.Intent createSAFIntent(String fileName) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_TITLE, fileName);
        return intent;
    }

    /**
     * 通过 SAF 写入内容
     *
     * @param context     上下文
     * @param uri         文档 Uri
     * @param content     要写入的内容
     * @return 是否成功
     */
    public static boolean writeContentViaSAF(Context context, Uri uri, String content) {
        try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                Log.e(TAG, "打开输出流失败");
                return false;
            }
            outputStream.write(content.getBytes("UTF-8"));
            outputStream.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "通过 SAF 写入内容失败", e);
            return false;
        }
    }
}
