package cn.chenxinjie.pathfinder.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简有界 TTL 缓存（单机、非持久）。
 *
 * <p>用于上传任务 {@code identifier → 归属人} 的短时缓存：组件在<b>每个分片请求</b>都会调用
 * {@code AccessControl.decide()}（{@code ResumableUploadService} 的 gate），本缓存把重复的归属
 * DB 查询降为一次，500MB/5MB 的上传由 100+ 次查询降到 1 次。</p>
 *
 * <p>实现：{@link LinkedHashMap}（accessOrder=true）实现 LRU 淘汰，方法级同步保证线程安全；
 * 读取时惰性剔除过期项。TTL 应保持较短（本项目 60s），以便软删除等归属变更尽快生效。</p>
 */
public class TtlCache<K, V> {

    private final int maxSize;
    private final long ttlMillis;
    private final Map<K, Entry<V>> map;

    public TtlCache(int maxSize, long ttlMillis) {
        this.maxSize = Math.max(1, maxSize);
        this.ttlMillis = Math.max(1, ttlMillis);
        this.map = new LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, TtlCache.Entry<V>> eldest) {
                return size() > TtlCache.this.maxSize;
            }
        };
    }

    /** 命中且未过期返回值，否则返回 {@code null} 并惰性清除。 */
    public synchronized V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt <= System.currentTimeMillis()) {
            map.remove(key);
            return null;
        }
        return entry.value;
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public synchronized void invalidate(K key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    private static final class Entry<V> {
        private final V value;
        private final long expireAt;

        private Entry(V value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
