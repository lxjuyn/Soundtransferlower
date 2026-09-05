# -*- coding: utf-8 -*-
p='BluetoothService.java'
s=open(p,encoding='utf-8').read()

old='''        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (isRunning) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        if (isTextMessage(buffer, bytes)) {
                            String message = new String(buffer, TEXT_PREFIX_BYTES.length, bytes - TEXT_PREFIX_BYTES.length);
                            handleTextMessage(message);
                        } else {
                            // 非文本数据
                            if (currentMode == MODE_TALKBACK) {
                                byte[] audioData = new byte[bytes];
                                System.arraycopy(buffer, 0, audioData, 0, bytes);
                                notifyTalkbackDataReceived(audioData, socket.getRemoteDevice().getAddress());

                                // ★★★ 发送语音确认消息 ★★★
                                sendConfirmMessage(System.currentTimeMillis());
                            } else {
                                LogUtil.w(TAG, "Received non-text data in chat mode");
                                notifyNonTextDataReceived(socket.getRemoteDevice().getAddress());
                            }
                        }
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }'''

new='''        public void run() {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
            int bytes;

            while (isRunning) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes <= 0) continue;
                    // —— 接收缓冲重组：修复粘包/半包/长消息被切碎误判为音频 ——
                    pending.write(buffer, 0, bytes);
                    byte[] data = pending.toByteArray();
                    pending.reset();

                    int cursor = 0;
                    boolean drained = (bytes < buffer.length); // 短读 ≈ socket 暂无更多数据
                    while (cursor < data.length) {
                        boolean isText = isTextMessage(data, cursor, data.length - cursor);
                        if (isText) {
                            int next = indexOfTextMarker(data, cursor + TEXT_PREFIX_BYTES.length);
                            if (next == -1) {
                                if (!drained && data.length - cursor < 64 * 1024) {
                                    // 半包：留到下一轮继续攒
                                    pending.write(data, cursor, data.length - cursor);
                                    break;
                                }
                                next = data.length; // 短读说明对端本条消息已发完
                            }
                            int contentLen = next - cursor - TEXT_PREFIX_BYTES.length;
                            String message = contentLen > 0
                                    ? new String(data, cursor + TEXT_PREFIX_BYTES.length, contentLen,
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    : "";
                            handleTextMessage(message);
                            cursor = next;
                        } else if (currentMode == MODE_TALKBACK) {
                            // 对讲：整块按音频处理（实时流语义，不回 CONFIRM——
                            // 逐帧回执会让包速率翻倍并在主线程形成 50 Toast/秒风暴）
                            byte[] audioData = new byte[data.length - cursor];
                            System.arraycopy(data, cursor, audioData, 0, audioData.length);
                            notifyTalkbackDataReceived(audioData, socket.getRemoteDevice().getAddress());
                            cursor = data.length;
                        } else {
                            LogUtil.w(TAG, "Received non-text data in chat mode");
                            notifyNonTextDataReceived(socket.getRemoteDevice().getAddress());
                            cursor = data.length;
                        }
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        /** 在 data 中从 from 起查找 "TXT:" 标记 */
        private int indexOfTextMarker(byte[] data, int from) {
            outer:
            for (int i = Math.max(from, 0); i <= data.length - TEXT_PREFIX_BYTES.length; i++) {
                for (int j = 0; j < TEXT_PREFIX_BYTES.length; j++) {
                    if (data[i + j] != TEXT_PREFIX_BYTES[j]) continue outer;
                }
                return i;
            }
            return -1;
        }

        private boolean isTextMessage(byte[] data, int offset, int length) {
            if (length < TEXT_PREFIX_BYTES.length) return false;
            for (int i = 0; i < TEXT_PREFIX_BYTES.length; i++) {
                if (data[offset + i] != TEXT_PREFIX_BYTES[i]) return false;
            }
            return true;
        }'''

assert old in s, 'read loop anchor missing'
s = s.replace(old, new, 1)

old2 = "        public void write(byte[] buffer, int mode) {\n" \
       "            try {\n" \
       "                outputStream.write(buffer);\n" \
       "                outputStream.flush();\n" \
       "                if (mode == MODE_CHAT) {\n" \
       "                    String message = new String(buffer);\n" \
       "                    if (message.startsWith(TEXT_PREFIX)) {\n" \
       "                        message = message.substring(TEXT_PREFIX.length());\n" \
       "                    }\n" \
       "                    // ★★★ 过滤确认消息，不保存 ★★★\n" \
       "                    if (!message.startsWith(\"CONFIRM:\") &&\n" \
       "                            !message.startsWith(CALL_REQUEST) && !message.startsWith(CALL_PREFIX) &&\n" \
       "                            !message.equals(CALL_ACCEPT) && !message.equals(CALL_REJECT) && !message.equals(CALL_HANGUP) &&\n" \
       "                            !message.startsWith(FILE_REQUEST_PREFIX) && !message.equals(FILE_ACCEPT) && !message.equals(FILE_REJECT)) {\n" \
       "                        saveMessageToFile(message, socket.getRemoteDevice().getAddress(), true);\n" \
       "                    }\n" \
       "                }\n" \
       "            } catch (IOException e) {\n" \
       "                LogUtil.e(TAG, \"Exception during write\", e);\n" \
       "            }\n" \
       "        }"

new2 = "        public void write(byte[] buffer, int mode) {\n" \
       "            try {\n" \
       "                // 加锁串行化：录音线程/UI 线程/读线程(CONFIRM) 可能并发写同一输出流，\n" \
       "                // 无锁会导致字节交错（对讲杂音、消息损坏的直接来源）\n" \
       "                synchronized (this) {\n" \
       "                    if (outputStream == null) return;\n" \
       "                    outputStream.write(buffer);\n" \
       "                    outputStream.flush();\n" \
       "                }\n" \
       "                if (mode == MODE_CHAT) {\n" \
       "                    String message = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);\n" \
       "                    if (message.startsWith(TEXT_PREFIX)) {\n" \
       "                        message = message.substring(TEXT_PREFIX.length());\n" \
       "                    }\n" \
       "                    // ★★★ 过滤确认/控制消息，不保存 ★★★\n" \
       "                    if (!message.startsWith(\"CONFIRM:\") &&\n" \
       "                            !message.startsWith(CALL_REQUEST) && !message.startsWith(CALL_PREFIX) &&\n" \
       "                            !message.equals(CALL_ACCEPT) && !message.equals(CALL_REJECT) && !message.equals(CALL_HANGUP) &&\n" \
       "                            !message.startsWith(FILE_REQUEST_PREFIX) && !message.equals(FILE_ACCEPT) && !message.equals(FILE_REJECT)) {\n" \
       "                        final String msg = message;\n" \
       "                        final String addr = socket.getRemoteDevice().getAddress();\n" \
       "                        // 落盘移出 socket 线程：旧实现每次全文件重写，历史越大越卡\n" \
       "                        saveExecutor.execute(() -> saveMessageToFile(msg, addr, true));\n" \
       "                    }\n" \
       "                }\n" \
       "            } catch (IOException e) {\n" \
       "                LogUtil.e(TAG, \"Exception during write\", e);\n" \
       "            }\n" \
       "        }"

assert old2 in s, 'write anchor missing'
s = s.replace(old2, new2, 1)

s = s.replace(
    'write(confirmMsg.getBytes(), MODE_CHAT);',
    'write(confirmMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8), MODE_CHAT);', 1)

anchor = '    private final CopyOnWriteArrayList<IMessageCallback.MessageCallback> messageCallbacks'
add = ('    /** 聊天记录异步落盘（单线程串行，保证追加顺序） */\n'
       '    private final java.util.concurrent.ExecutorService saveExecutor =\n'
       '            java.util.concurrent.Executors.newSingleThreadExecutor();\n')
if anchor in s:
    s = s.replace(anchor, add + anchor, 1)
else:
    print('WARN: callback anchor miss')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('BluetoothService WP2 core done')
