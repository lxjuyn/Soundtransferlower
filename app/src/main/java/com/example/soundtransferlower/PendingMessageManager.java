package com.example.soundtransferlower;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PendingMessageManager {
    private static final String FILE_NAME = "pending_messages.json";
    private static PendingMessageManager instance;
    private Context context;
    private List<PendingMessage> pendingMessages = new ArrayList<>();

    private PendingMessageManager(Context context) {
        this.context = context.getApplicationContext();
        loadFromFile();
    }

    public static synchronized PendingMessageManager getInstance(Context context) {
        if (instance == null) instance = new PendingMessageManager(context);
        return instance;
    }

    public synchronized void addMessage(PendingMessage msg) {
        pendingMessages.add(msg);
        saveToFile();
    }

    public synchronized void removeMessage(String id) {
        Iterator<PendingMessage> it = pendingMessages.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(id)) {
                it.remove();
                break;
            }
        }
        saveToFile();
    }

    public synchronized List<PendingMessage> getMessagesForDevice(String deviceAddress) {
        List<PendingMessage> result = new ArrayList<>();
        for (PendingMessage msg : pendingMessages) {
            if (msg.targetDeviceAddress.equals(deviceAddress)) result.add(msg);
        }
        return result;
    }

    public synchronized List<PendingMessage> getAllMessages() {
        return new ArrayList<>(pendingMessages);
    }

    // ★ 新增方法
    public synchronized PendingMessage getMessageById(String id) {
        for (PendingMessage msg : pendingMessages) {
            if (msg.id.equals(id)) return msg;
        }
        return null;
    }

    public synchronized void clearAll() {
        pendingMessages.clear();
        saveToFile();
    }

    private void saveToFile() {
        try {
            JSONArray array = new JSONArray();
            for (PendingMessage msg : pendingMessages) {
                JSONObject obj = new JSONObject();
                obj.put("id", msg.id);
                obj.put("type", msg.type);
                obj.put("content", msg.content);
                obj.put("targetDeviceAddress", msg.targetDeviceAddress);
                obj.put("targetDeviceName", msg.targetDeviceName);
                obj.put("reason", msg.reason);
                obj.put("timestamp", msg.timestamp);
                array.put(obj);
            }
            File file = new File(context.getFilesDir(), FILE_NAME);
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(array.toString());
            writer.close();
            fos.close();
        } catch (Exception e) {
            LogUtil.e("PendingMsgManager", "保存失败", e);
        }
    }

    private void loadFromFile() {
        pendingMessages.clear();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;
        try {
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) != -1) sb.append(buffer, 0, len);
            reader.close();
            fis.close();
            String json = sb.toString();
            if (json.isEmpty()) return;
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                PendingMessage msg = new PendingMessage();
                msg.id = obj.getString("id");
                msg.type = obj.getInt("type");
                msg.content = obj.getString("content");
                msg.targetDeviceAddress = obj.getString("targetDeviceAddress");
                msg.targetDeviceName = obj.getString("targetDeviceName");
                msg.reason = obj.getString("reason");
                msg.timestamp = obj.getLong("timestamp");
                pendingMessages.add(msg);
            }
        } catch (Exception e) {
            LogUtil.e("PendingMsgManager", "加载失败", e);
        }
    }
}