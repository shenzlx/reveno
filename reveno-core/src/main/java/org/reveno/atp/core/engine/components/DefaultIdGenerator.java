/** 
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.core.engine.components;

import org.reveno.atp.commons.ByteArrayWrapper;
import org.reveno.atp.commons.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.reveno.atp.api.commands.CommandContext;
import org.reveno.atp.api.transaction.TransactionContext;
import org.reveno.atp.core.api.IdGenerator;

import java.io.Serializable;
import java.util.*;
import java.util.function.BiConsumer;

import static org.reveno.atp.utils.BinaryUtils.crc32;
import static org.reveno.atp.utils.BinaryUtils.sha1;

public class DefaultIdGenerator implements IdGenerator, BiConsumer<DefaultIdGenerator.NextIdTransaction, TransactionContext> {
	
	@Override
	public void context(CommandContext context) {
		this.context = context;
	}

	@Override
	public long next(Class<?> entityType) {
		registerIfRequired(entityType);

		byte[] sha = null;
		long crc = crcNames.getLong(entityType);
		if (crc == -1) {
			crc = 0;
			sha = sha1Names.get(entityType);
		}
		IdsBundle bundle = context.repo().get(IdsBundle.class, 0L);
		long lastId = lastIds.getOrDefault(entityType, 0L);
		long id = lastId + (bundle != null ? bundle.get(sha, crc) + 1 : 1);
		lastIds.put(entityType, lastId + 1);

		context.executeTxAction(new NextIdTransaction(null, id, crc, sha));
		return id;
	}
	
	@Override
	public void accept(DefaultIdGenerator.NextIdTransaction t, TransactionContext u) {
		if (lastIds.size() > 0)
			lastIds.clear();
		u.repo().merge(0, IdsBundle.class,
				() -> new IdsBundle().store(t, t.id),
				(id, b) -> b.store(t, t.id));
	}

	protected void registerIfRequired(Class<?> entityType) {
		if (!sha1Names.containsKey(entityType)) {
			byte[] shaKey = sha1(entityType.getName());
			long crc = crc32(entityType.getName());
			if (!registeredCrc.containsKey(crc)) {
				registeredCrc.put(crc, entityType);
				crcNames.put(entityType, crc);
			}
			sha1Names.put(entityType, shaKey);
		}
	}

	protected Object2LongMapEx<Class<?>> lastIds = new Object2LongOpenHashMapEx<>();

	protected Long2ObjectMap<Class<?>> registeredCrc = new Long2ObjectOpenHashMap<>(128);
	protected Map<Class<?>, byte[]> sha1Names = new HashMap<>(128);
	protected Object2LongMap<Class<?>> crcNames = new Object2LongOpenHashMap<>(128);

	protected CommandContext context;

	public DefaultIdGenerator() {
		crcNames.defaultReturnValue(-1L);
	}
	
	public static class IdsBundle implements Serializable {
		private static final long serialVersionUID = 1L;

		public long get(long crc) {
			return crcIds.getOrDefault(crc, 0L);
		}

		public long get(byte[] sha) {
			Long l;
			return (l = shaIds.get(sha)) == null ? 0 : l;
		}

		public long get(byte[] sha, long crc) {
			if (sha != null) {
				return get(sha);
			} else {
				return get(crc);
			}
		}
		
		public IdsBundle store(long crc, long id) {
			crcIds.put(crc, id);
			return this;
		}

		/*
			Quite not GC friendly, but SHA1 would be used only on CRC32 collision, hence - VERY rarely
		 */
		public IdsBundle store(byte[] sha, long id) {
			shaIds.put(new ByteArrayWrapper(sha), id);
			return this;
		}

		public IdsBundle store(NextIdTransaction t, long id) {
			if (t.entityType != null) {
				// for compatibility only, not used in runtime
				byte[] shaKey = sha1(t.entityType.getName());
				long crc = crc32(t.entityType.getName());
				shaIds.put(new ByteArrayWrapper(shaKey), id);
				crcIds.put(crc, id);
				return this;
			} else if (t.typeSha1 != null) {
				return store(t.typeSha1, id);
			} else {
				return store(t.typeCrc, id);
			}
		}

		//@Deprecated
		protected Object2LongOpenHashMapEx<Class<?>> ids;
		protected Long2LongOpenHashMap crcIds = new Long2LongOpenHashMap();
		protected HashMap<ByteArrayWrapper, Long> shaIds = new HashMap<>();
	}
	
	public static class NextIdTransaction implements Serializable {
		//@Deprecated
		public Class<?> entityType;
		public long id;
		public long typeCrc;
		public byte[] typeSha1;
		
		public NextIdTransaction(Class<?> entityType, long id, long typeCrc, byte[] typeSha1) {
			this.entityType = entityType;
			this.id = id;
			this.typeCrc = typeCrc;
			this.typeSha1 = typeSha1;
		}

		public NextIdTransaction() {
		}
	}

	public interface Object2LongMapEx<T> extends Object2LongMap<T> {
		long getOrDefault(T key, long value);
	}

	public static class Object2LongOpenHashMapEx<T> extends Object2LongOpenHashMap<T> implements Object2LongMapEx<T> {
		@Override
		public long getOrDefault(T key, long value) {
			if (this.containsKey(key)) {
				return this.getLong(key);
			} else {
				return value;
			}
		}
	}

}
