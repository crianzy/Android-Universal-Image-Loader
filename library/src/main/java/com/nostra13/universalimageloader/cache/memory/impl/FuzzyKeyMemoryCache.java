/*******************************************************************************
 * Copyright 2011-2014 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.cache.memory.impl;

import android.graphics.Bitmap;

import com.nostra13.universalimageloader.cache.memory.MemoryCache;

import java.util.Collection;
import java.util.Comparator;

/**
 * Decorator for {@link MemoryCache}. Provides special feature for cache: some different keys are considered as
 * equals (using {@link Comparator comparator}). And when you try to put some value into cache by key so entries with
 * "equals" keys will be removed from cache before.<br />
 * <b>NOTE:</b> Used for internal needs. Normally you don't need to use this class.
 *
 * MemoryCache 的一个装饰类, 提供一些不同的功能
 *
 * 这里 提供了一个 Comparator<String> 可以自己定义 那种情况下的 key 是相同的
 * 而不纯粹只是  String 之间的 是否 equals
 *
 * 一般不用他
 *
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.0.0
 */
public class FuzzyKeyMemoryCache implements MemoryCache {

	private final MemoryCache cache;
	private final Comparator<String> keyComparator;

	/**
	 *
	 * @param cache  其他 缓存
	 * @param keyComparator  String的一个 比较器
	 */
	public FuzzyKeyMemoryCache(MemoryCache cache, Comparator<String> keyComparator) {
		this.cache = cache;
		this.keyComparator = keyComparator;
	}

	@Override
	public boolean put(String key, Bitmap value) {
		// Search equal key and remove this entry
		synchronized (cache) {
			String keyToRemove = null;
			for (String cacheKey : cache.keys()) {
				// 这里用 compare 主要是为了可以自定义的 定义 Key 在什么样的状态下 是相同的
				// 如果 是equals 那么这个类 就没意义了
				if (keyComparator.compare(key, cacheKey) == 0) {
					// 找到 之前 "相同的"key了
					keyToRemove = cacheKey;
					break;
				}
			}
			if (keyToRemove != null) {
				// 把 找到的相同的 key 然后remove
				cache.remove(keyToRemove);
			}
		}
		return cache.put(key, value);
	}

	@Override
	public Bitmap get(String key) {
		return cache.get(key);
	}

	@Override
	public Bitmap remove(String key) {
		return cache.remove(key);
	}

	@Override
	public void clear() {
		cache.clear();
	}

	@Override
	public Collection<String> keys() {
		return cache.keys();
	}
}
