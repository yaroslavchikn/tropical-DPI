package com.youtube.turbo;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class DpiInterceptor {
    private static final String TAG = "DpiInterceptor";
    
    // Список доменов для подмены
    private static final String[] TARGET_DOMAINS = {
        "googlevideo.com",
        "youtube.com",
        "ytimg.com",
        "google.com",
        "ggpht.com",
        "youtu.be"
    };
    
    private static final Pattern SNI_PATTERN = Pattern.compile(
        ".*(googlevideo\\.com|youtube\\.com|ytimg\\.com|google\\.com|ggpht\\.com|youtu\\.be).*",
        Pattern.CASE_INSENSITIVE
    );
    
    // Кэш для подмены
    private static final Map<String, String> DOMAIN_MAP = new HashMap<>();
    static {
        DOMAIN_MAP.put("googlevideo.com", "accounts.google.com");
        DOMAIN_MAP.put("youtube.com", "google.com");
        DOMAIN_MAP.put("ytimg.com", "google.com");
        DOMAIN_MAP.put("ggpht.com", "google.com");
        DOMAIN_MAP.put("youtu.be", "google.com");
    }

    public static void interceptConnection(SocketChannel client) {
        Log.d(TAG, "Новое подключение: " + client.socket().getRemoteSocketAddress());
        // Здесь можно добавить логирование подключений
    }

    public static long processPacket(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        long bytesRead = 0;
        
        try {
            // Пытаемся прочитать данные
            bytesRead = channel.read(buffer);
            if (bytesRead <= 0) return 0;
            
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            
            // Проверяем, является ли пакет SSL/TLS (начинается с 0x16 0x03)
            if (data.length > 2 && data[0] == 0x16 && data[1] == 0x03) {
                byte[] modifiedData = modifySni(data);
                if (modifiedData != null) {
                    // Отправляем модифицированный пакет
                    ByteBuffer modifiedBuffer = ByteBuffer.wrap(modifiedData);
                    channel.write(modifiedBuffer);
                    return modifiedData.length;
                }
            }
            
            // Если не SSL или не удалось модифицировать, передаём как есть
            buffer.rewind();
            channel.write(buffer);
            return bytesRead;
            
        } catch (IOException e) {
            Log.e(TAG, "Ошибка обработки пакета", e);
            throw e;
        }
    }

    private static byte[] modifySni(byte[] data) {
        try {
            // Ищем SNI расширение в ClientHello
            // TLS ClientHello структура: [Record] [Handshake] [Extensions]
            if (data.length < 43) return null;
            
            // Пропускаем заголовок Record (5 байт)
            int pos = 5;
            
            // Проверяем тип Handshake (0x01 = ClientHello)
            if (data[pos] != 0x01) return null;
            pos += 4; // Пропускаем длину handshake
            
            // Пропускаем версию и random (32 байта)
            pos += 34;
            
            // Длина session ID
            int sessionIdLength = data[pos] & 0xFF;
            pos += 1 + sessionIdLength;
            
            // Длина cipher suites
            int cipherLength = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2 + cipherLength;
            
            // Длина compression methods
            int compressionLength = data[pos] & 0xFF;
            pos += 1 + compressionLength;
            
            // Теперь идём в расширения
            if (pos + 2 > data.length) return null;
            int extensionsLength = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            
            int extensionsEnd = pos + extensionsLength;
            if (extensionsEnd > data.length) return null;
            
            // Ищем SNI расширение (тип 0x0000)
            while (pos + 4 <= extensionsEnd) {
                int extType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int extLength = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                pos += 4;
                
                if (extType == 0x0000) { // SNI
                    // Парсим ServerName
                    int sniPos = pos;
                    int serverNameListLen = ((data[sniPos] & 0xFF) << 8) | (data[sniPos + 1] & 0xFF);
                    sniPos += 2;
                    
                    // Проверяем тип ServerName (0x00 = host_name)
                    if (sniPos < data.length && data[sniPos] == 0x00) {
                        sniPos += 1;
                        int hostNameLen = ((data[sniPos] & 0xFF) << 8) | (data[sniPos + 1] & 0xFF);
                        sniPos += 2;
                        
                        if (sniPos + hostNameLen <= data.length) {
                            String hostname = new String(data, sniPos, hostNameLen, StandardCharsets.UTF_8);
                            Log.d(TAG, "Найден SNI: " + hostname);
                            
                            // Проверяем, нужно ли подменить
                            if (SNI_PATTERN.matcher(hostname).matches()) {
                                String newHostname = substituteDomain(hostname);
                                if (newHostname != null && !newHostname.equals(hostname)) {
                                    Log.d(TAG, "Подмена SNI: " + hostname + " -> " + newHostname);
                                    
                                    // Создаём модифицированный пакет
                                    byte[] newData = Arrays.copyOf(data, data.length);
                                    byte[] newHostBytes = newHostname.getBytes(StandardCharsets.UTF_8);
                                    
                                    // Обновляем длину hostname
                                    newData[sniPos - 2] = (byte) ((newHostBytes.length >> 8) & 0xFF);
                                    newData[sniPos - 1] = (byte) (newHostBytes.length & 0xFF);
                                    
                                    // Обновляем длину server_name_list
                                    int newServerNameListLen = 3 + newHostBytes.length; // 1 byte type + 2 bytes length
                                    newData[pos] = (byte) ((newServerNameListLen >> 8) & 0xFF);
                                    newData[pos + 1] = (byte) (newServerNameListLen & 0xFF);
                                    
                                    // Копируем новый hostname
                                    System.arraycopy(newHostBytes, 0, newData, sniPos, newHostBytes.length);
                                    
                                    return newData;
                                }
                            }
                        }
                    }
                }
                pos += extLength;
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка модификации SNI", e);
        }
        return null;
    }

    private static String substituteDomain(String hostname) {
        for (Map.Entry<String, String> entry : DOMAIN_MAP.entrySet()) {
            if (hostname.contains(entry.getKey())) {
                return hostname.replace(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }
}
