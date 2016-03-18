/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nostra13.universalimageloader.cache.disc.impl.ext;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache
 * entry has a string key and a fixed number of values. Each key must match
 * the regex <strong>[a-z0-9_-]{1,64}</strong>. Values are byte sequences,
 * accessible as streams or files. Each value must be between {@code 0} and
 * {@code Integer.MAX_VALUE} bytes in length.
 *
 * <p>The cache stores its data in a directory on the filesystem. This
 * directory must be exclusive to the cache; the cache may delete or overwrite
 * files from its directory. It is an error for multiple processes to use the
 * same cache directory at the same time.
 *
 * <p>This cache limits the number of bytes that it will store on the
 * filesystem. When the number of stored bytes exceeds the limit, the cache will
 * remove entries in the background until the limit is satisfied. The limit is
 * not strict: the cache may temporarily exceed it while waiting for files to be
 * deleted. The limit does not include filesystem overhead or the cache
 * journal so space-sensitive applications should set a conservative limit.
 *
 * <p>Clients call {@link #edit} to create or update the values of an entry. An
 * entry may have only one editor at one time; if a value is not available to be
 * edited then {@link #edit} will return null.
 * <ul>
 * <li>When an entry is being <strong>created</strong> it is necessary to
 * supply a full set of values; the empty value should be used as a
 * placeholder if necessary.
 * <li>When an entry is being <strong>edited</strong>, it is not necessary
 * to supply data for every value; values default to their previous
 * value.
 * </ul>
 * Every {@link #edit} call must be matched by a call to {@link Editor#commit}
 * or {@link Editor#abort}. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 *
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will
 * observe the value at the time that {@link #get} was called. Updates and
 * removals after the call do not impact ongoing reads.
 *
 * <p>This class is tolerant of some I/O errors. If files are missing from the
 * filesystem, the corresponding entries will be dropped from the cache. If
 * an error occurs while writing a cache value, the edit will fail silently.
 * Callers should handle other problems by catching {@code IOException} and
 * responding appropriately.
 *
 * 这个是 图片缓存的记录文件 journal 的相关类
 */
final class DiskLruCache implements Closeable {
	static final String JOURNAL_FILE = "journal";
	static final String JOURNAL_FILE_TEMP = "journal.tmp";
	static final String JOURNAL_FILE_BACKUP = "journal.bkp";
	static final String MAGIC = "libcore.io.DiskLruCache";
	static final String VERSION_1 = "1";
	static final long ANY_SEQUENCE_NUMBER = -1;
	static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
	private static final String CLEAN = "CLEAN";
	private static final String DIRTY = "DIRTY";
	private static final String REMOVE = "REMOVE";
	private static final String READ = "READ";

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *     libcore.io.DiskLruCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.DiskLruCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the lengths of each of
     *     its values.
     *   o READ lines track accesses for LRU.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     */

	//journal  日志 报告的意思

	private final File directory;
	private final File journalFile;
	private final File journalFileTmp;
	private final File journalFileBackup;
	private final int appVersion;
	private long maxSize;
	private int maxFileCount;
	// 记录中 值的数量  LruDiskCache 设置为1
	private final int valueCount;
	private long size = 0;
	private int fileCount = 0;
	private Writer journalWriter;

	/**
	 * LinkedHashMap LRL
	 */
	private final LinkedHashMap<String, Entry> lruEntries =
			new LinkedHashMap<String, Entry>(0, 0.75f, true);
	private int redundantOpCount;

	/**
	 * To differentiate between old and current snapshots, each entry is given
	 * a sequence number each time an edit is committed. A snapshot is stale if
	 * its sequence number is not equal to its entry's sequence number.
	 */
	private long nextSequenceNumber = 0;

	/** This cache uses a single background thread to evict entries.
	 * 这个缓存 使用独立的线程池
	 * */
	final ThreadPoolExecutor executorService =
			new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	/**
	 * 当超过缓存 大小的时候 做一些 清除操作
	 */
	private final Callable<Void> cleanupCallable = new Callable<Void>() {
		public Void call() throws Exception {
			synchronized (DiskLruCache.this) {
				if (journalWriter == null) {
					return null; // Closed.
				}
				trimToSize();
				trimToFileCount();
				if (journalRebuildRequired()) {
					rebuildJournal();
					redundantOpCount = 0;
				}
			}
			return null;
		}
	};

	private DiskLruCache(File directory, int appVersion, int valueCount, long maxSize, int maxFileCount) {
		this.directory = directory;
		this.appVersion = appVersion;
		this.journalFile = new File(directory, JOURNAL_FILE);
		this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
		this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
		this.valueCount = valueCount;
		this.maxSize = maxSize;
		this.maxFileCount = maxFileCount;
	}

	/**
	 * Opens the cache in {@code directory}, creating a cache if none exists
	 * there.
	 *
	 * @param directory a writable directory
	 * @param valueCount the number of values per cache entry. Must be positive.
	 * @param maxSize the maximum number of bytes this cache should use to store
	 * @param maxFileCount the maximum file count this cache should store
	 * @throws IOException if reading or writing the cache directory fails
	 */
	public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize, int maxFileCount)
			throws IOException {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("maxSize <= 0");
		}
		if (maxFileCount <= 0) {
			throw new IllegalArgumentException("maxFileCount <= 0");
		}
		if (valueCount <= 0) {
			throw new IllegalArgumentException("valueCount <= 0");
		}

		// If a bkp file exists, use it instead.
		// 备份文件
		File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
		if (backupFile.exists()) {
			File journalFile = new File(directory, JOURNAL_FILE);
			// If journal file also exists just delete backup file.
			// 如果备份文件 和 日志文件 都存在 那么删除 备份文件
			// 否则 把备份文件 改成 日志文件
			if (journalFile.exists()) {
				backupFile.delete();
			} else {
				renameTo(backupFile, journalFile, false);
			}
		}

		// Prefer to pick up where we left off.
		// new 一个 DiskLruCache
		DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize, maxFileCount);
		if (cache.journalFile.exists()) {
			// 如果该缓存的日记文件穿在
			try {
				cache.readJournal();
				// 由于这是重新 开的 日志文件, 表明 app 刚启动
				// 原来保存的那些  Dirty 的脏的数据的 文件可以删掉
				// 不是删除日志信息中的那行 而是 删除 entry 的 DirtyFile
				cache.processJournal();
				cache.journalWriter = new BufferedWriter(
						new OutputStreamWriter(new FileOutputStream(cache.journalFile, true), Util.US_ASCII));
				return cache;
			} catch (IOException journalIsCorrupt) {
				System.out
						.println("DiskLruCache "
								+ directory
								+ " is corrupt: "
								+ journalIsCorrupt.getMessage()
								+ ", removing");
				cache.delete();
			}
		}

		// Create a new empty cache.
		directory.mkdirs();
		cache = new DiskLruCache(directory, appVersion, valueCount, maxSize, maxFileCount);
		cache.rebuildJournal();
		return cache;
	}

	/**
	 * 读取日志信息
	 *
	 * 吧 日志信息转化为 LinkHashMap<String Entity>
	 * @throws IOException
	 */
	private void readJournal() throws IOException {
		// 每行的 reader
		StrictLineReader reader = new StrictLineReader(new FileInputStream(journalFile), Util.US_ASCII);
		try {
			String magic = reader.readLine();// libcore.io.DiskLruCache
			String version = reader.readLine();// 缓存版本
			String appVersionString = reader.readLine();// app版本
			String valueCountString = reader.readLine();// 记录数量
			String blank = reader.readLine();// 一个空行
			if (!MAGIC.equals(magic)
					|| !VERSION_1.equals(version)
					|| !Integer.toString(appVersion).equals(appVersionString)
					|| !Integer.toString(valueCount).equals(valueCountString)
					|| !"".equals(blank)) {
				// 如果基本信息不匹配 抛出异常
				throw new IOException("unexpected journal header: [" + magic + ", " + version + ", "
						+ valueCountString + ", " + blank + "]");
			}

			int lineCount = 0;
			while (true) {
				try {
					// 这里就是 吧 日志信息 转换为  LinkHashMap<String Entity>
					readJournalLine(reader.readLine());
					lineCount++;
				} catch (EOFException endOfJournal) {
					break;
				}
			}
			redundantOpCount = lineCount - lruEntries.size();
		} finally {
			Util.closeQuietly(reader);
		}
	}

	/**
	 * 读取 日志文件中的 每行的信息
	 * @param line
	 * @throws IOException
	 */
	private void readJournalLine(String line) throws IOException {
		int firstSpace = line.indexOf(' ');
		// 第一个空格的位置
		if (firstSpace == -1) {
			// 没找到空格 抛出异常
			throw new IOException("unexpected journal line: " + line);
		}

		int keyBegin = firstSpace + 1;
		// 第二个空格的位置
		int secondSpace = line.indexOf(' ', keyBegin);
		final String key;
		if (secondSpace == -1) {
			// 如果没有第二个空格
			// 那么 第一个空格后面的就是  key的值了
			// 如: REMOVE 335c4c6028171cfddfbaae1a9c313c52
			key = line.substring(keyBegin);
			// 如果第一个字段的值  是  REMOVE
			if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
				// lru 中 删除这个 记录
				lruEntries.remove(key);
				return;
			}
		} else {
			// 如果有第二个空格 那么 key 就是 第一个空格到第二个空格之间了
			key = line.substring(keyBegin, secondSpace);
		}

		// 通过key 获取 实体类
		Entry entry = lruEntries.get(key);
		if (entry == null) {
			// 如果没有,  new 一个注入进去
			entry = new Entry(key);
			lruEntries.put(key, entry);
		}

		if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
			// 如果有第二个空格 且 开头是 clean
			// 数据如下: CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
			// 上面例子的 patrs 的值 就是  1600 234
			String[] parts = line.substring(secondSpace + 1).split(" ");
			// CLEAN 状态下的数据可读
			entry.readable = true;
			entry.currentEditor = null;
			entry.setLengths(parts);
		} else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
			// DIRTY 1ab96a171faeeee38496d8b330771a7a
			entry.currentEditor = new Editor(entry);
		} else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
			// READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
			// This work was already done by calling lruEntries.get().
		} else {
			throw new IOException("unexpected journal line: " + line);
		}
	}

	/**
	 * Computes the initial size and collects garbage as a part of opening the
	 * cache. Dirty entries are assumed to be inconsistent and will be deleted.
	 *
	 * 计算所有文件的 大小 和文件数量
	 * 和删除掉一些一些张脏数据
	 *
	 */
	private void processJournal() throws IOException {
		// 删除temp文件
		deleteIfExists(journalFileTmp);
		for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
			Entry entry = i.next();
			if (entry.currentEditor == null) {
				// 当前实体的编辑器 是 null 表示 不在编辑状态
				// 计算文件大小 和文件数量
				for (int t = 0; t < valueCount; t++) {
					size += entry.lengths[t];
					fileCount++;
				}
			} else {
				// 表示在编辑状态  在编辑状态的 脏数据 删除
				entry.currentEditor = null;
				for (int t = 0; t < valueCount; t++) {
					// 需要删除 掉 一些 需要清除 和脏 的数据
					deleteIfExists(entry.getCleanFile(t));
					deleteIfExists(entry.getDirtyFile(t));
				}
				i.remove();
			}
		}
	}

	/**
	 * Creates a new journal that omits redundant information. This replaces the
	 * current journal if it exists.
	 *
	 * 抽检构建 一个新的  日志文件
	 */
	private synchronized void rebuildJournal() throws IOException {
		if (journalWriter != null) {
			journalWriter.close();
		}

		Writer writer = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(journalFileTmp), Util.US_ASCII));
		try {
			writer.write(MAGIC);
			writer.write("\n");
			writer.write(VERSION_1);
			writer.write("\n");
			writer.write(Integer.toString(appVersion));
			writer.write("\n");
			writer.write(Integer.toString(valueCount));
			writer.write("\n");
			writer.write("\n");
			// 上面是些基本信息

			for (Entry entry : lruEntries.values()) {
				if (entry.currentEditor != null) {
					// 不为空 表示 数据室脏的
					writer.write(DIRTY + ' ' + entry.key + '\n');
				} else {
					writer.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
				}
			}
		} finally {
			writer.close();
		}

		// 先写到 temp 文件中 然后再重命名 到 journalFileBackup 和  journalFile 中
		if (journalFile.exists()) {
			renameTo(journalFile, journalFileBackup, true);
		}
		renameTo(journalFileTmp, journalFile, false);
		journalFileBackup.delete();

		// 上次写的  Writer 关闭了 这里重新new 一个新的额
		journalWriter = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(journalFile, true), Util.US_ASCII));
	}

	private static void deleteIfExists(File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException();
		}
	}

	private static void renameTo(File from, File to, boolean deleteDestination) throws IOException {
		if (deleteDestination) {
			deleteIfExists(to);
		}
		if (!from.renameTo(to)) {
			throw new IOException();
		}
	}

	/**
	 * Returns a snapshot of the entry named {@code key}, or null if it doesn't
	 * exist is not currently readable. If a value is returned, it is moved to
	 * the head of the LRU queue.
	 */
	public synchronized Snapshot get(String key) throws IOException {
		// 校验
		checkNotClosed();
		validateKey(key);
		Entry entry = lruEntries.get(key);
		if (entry == null) {
			return null;
		}

		if (!entry.readable) {
			// 不能读 返回null
			return null;
		}

		// Open all streams eagerly to guarantee that we see a single published
		// snapshot. If we opened streams lazily then the streams could come
		// from different edits.
		// 读取 clean fiel 的文件s
		// 可能有多个文件 LruDiskCache 设置的是1个
		File[] files = new File[valueCount];
		InputStream[] ins = new InputStream[valueCount];
		try {
			File file;
			for (int i = 0; i < valueCount; i++) {
				file = entry.getCleanFile(i);
				files[i] = file;
				ins[i] = new FileInputStream(file);
			}
		} catch (FileNotFoundException e) {
			// A file must have been deleted manually!
			for (int i = 0; i < valueCount; i++) {
				if (ins[i] != null) {
					Util.closeQuietly(ins[i]);
				} else {
					break;
				}
			}
			return null;
		}

		// 读的记录 +1
		redundantOpCount++;
		journalWriter.append(READ + ' ' + key + '\n');
		if (journalRebuildRequired()) {
			executorService.submit(cleanupCallable);
		}
		// 创建一个快照 并返回
		return new Snapshot(key, entry.sequenceNumber, files, ins, entry.lengths);
	}

	/**
	 * Returns an editor for the entry named {@code key}, or null if another
	 * edit is in progress.
	 */
	public Editor edit(String key) throws IOException {
		return edit(key, ANY_SEQUENCE_NUMBER);
	}

	private synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
		checkNotClosed();
		validateKey(key);
		Entry entry = lruEntries.get(key);
		// 一般 expectedSequenceNumber == ANY_SEQUENCE_NUMBER
		if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
				|| entry.sequenceNumber != expectedSequenceNumber)) {
			return null; // Snapshot is stale.
		}
		if (entry == null) {
			// 如果entry 为空 更具key new 一个 然后加入到 lru map中
			entry = new Entry(key);
			lruEntries.put(key, entry);
		} else if (entry.currentEditor != null) {
			// 如果 entry 不为空  但是 currentEditor 但是哟其他编辑器在编辑
			return null; // Another edit is in progress.
		}

		Editor editor = new Editor(entry);
		// 设置当前  entry 的 currentEditor 表示正在编辑
		entry.currentEditor = editor;

		// Flush the journal before creating files to prevent file leaks.
		// 记录一行日志 , 说原来的key 对应的 是 脏数据
		journalWriter.write(DIRTY + ' ' + key + '\n');
		journalWriter.flush();
		return editor;
	}

	/** Returns the directory where this cache stores its data. */
	public File getDirectory() {
		return directory;
	}

	/**
	 * Returns the maximum number of bytes that this cache should use to store
	 * its data.
	 */
	public synchronized long getMaxSize() {
		return maxSize;
	}

	/** Returns the maximum number of files that this cache should store */
	public synchronized int getMaxFileCount() {
		return maxFileCount;
	}

	/**
	 * Changes the maximum number of bytes the cache can store and queues a job
	 * to trim the existing store, if necessary.
	 */
	public synchronized void setMaxSize(long maxSize) {
		this.maxSize = maxSize;
		executorService.submit(cleanupCallable);
	}

	/**
	 * Returns the number of bytes currently being used to store the values in
	 * this cache. This may be greater than the max size if a background
	 * deletion is pending.
	 */
	public synchronized long size() {
		return size;
	}

	/**
	 * Returns the number of files currently being used to store the values in
	 * this cache. This may be greater than the max file count if a background
	 * deletion is pending.
	 */
	public synchronized long fileCount() {
		return fileCount;
	}

	/**
	 * 完成编辑
	 * @param editor
	 * @param success
	 * @throws IOException
	 */
	private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
		Entry entry = editor.entry;
		if (entry.currentEditor != editor) {
			throw new IllegalStateException();
		}

		// If this edit is creating the entry for the first time, every index must have a value.
		// 如果这个编辑是建立在第一次进入 ，每个 index 都会有个值
		if (success && !entry.readable) {
			// editor.abort(); 也只是在掉一遍 completeEdit 方法 不过 success false
			for (int i = 0; i < valueCount; i++) {
				if (!editor.written[i]) {
					// 如果不能读 也不能写 报错
					editor.abort();
					throw new IllegalStateException("Newly created entry didn't create value for index " + i);
				}
				if (!entry.getDirtyFile(i).exists()) {
					// 如果文件不存在
					editor.abort();
					return;
				}
			}
		}

		// TODO 想说  valueCount 是1  现在还没高度  valueCount 是干嘛用的?
		for (int i = 0; i < valueCount; i++) {
			// 获取脏的的文件
			File dirty = entry.getDirtyFile(i);
			if (success) {
				if (dirty.exists()) {
					File clean = entry.getCleanFile(i);
					//dirty 重命名到 clean
					dirty.renameTo(clean);
					long oldLength = entry.lengths[i];
					long newLength = clean.length();
					entry.lengths[i] = newLength;
					size = size - oldLength + newLength;
					fileCount++;
				}
			} else {
				// 如果失败 那么 会 删除脏的文件
				deleteIfExists(dirty);
			}
		}

		redundantOpCount++;
		entry.currentEditor = null;
		if (entry.readable | success) {
			// 编辑文件成功 修改为刻度
			entry.readable = true;
			//如果成功  加入一条清除记录
			journalWriter.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
			if (success) {
				entry.sequenceNumber = nextSequenceNumber++;
			}
		} else {
			// 失败的话, 加入一条 移除记录
			lruEntries.remove(entry.key);
			journalWriter.write(REMOVE + ' ' + entry.key + '\n');
		}
		journalWriter.flush();


		// 如果 大小超了先出 做一些 清除任务
		if (size > maxSize || fileCount > maxFileCount || journalRebuildRequired()) {
			executorService.submit(cleanupCallable);
		}
	}

	/**
	 * We only rebuild the journal when it will halve the size of the journal
	 * and eliminate at least 2000 ops.
	 */
	private boolean journalRebuildRequired() {
		final int redundantOpCompactThreshold = 2000;
		return redundantOpCount >= redundantOpCompactThreshold //
				&& redundantOpCount >= lruEntries.size();
	}

	/**
	 * Drops the entry for {@code key} if it exists and can be removed. Entries
	 * actively being edited cannot be removed.
	 *
	 *
	 * @return true if an entry was removed.
	 */
	public synchronized boolean remove(String key) throws IOException {
		// 检查认知文件 输出流
		checkNotClosed();
		// 验证key
		validateKey(key);
		Entry entry = lruEntries.get(key);
		if (entry == null || entry.currentEditor != null) {
			//为空 或  在编辑 不处理
			return false;
		}

		for (int i = 0; i < valueCount; i++) {
			File file = entry.getCleanFile(i);
			if (file.exists() && !file.delete()) {
				throw new IOException("failed to delete " + file);
			}
			// size  fileCount 减小
			size -= entry.lengths[i];
			fileCount--;
			entry.lengths[i] = 0;
		}

		// 操作数+1
		redundantOpCount++;
		// 计入 remove 操作
		journalWriter.append(REMOVE + ' ' + key + '\n');
		// lru remove
		lruEntries.remove(key);

		// 这里是 操作次数 >2000 就需要重新构建了
		if (journalRebuildRequired()) {
			// 判断是否需要重新构建 日志文件
			executorService.submit(cleanupCallable);
		}

		return true;
	}

	/** Returns true if this cache has been closed. */
	public synchronized boolean isClosed() {
		return journalWriter == null;
	}

	private void checkNotClosed() {
		if (journalWriter == null) {
			throw new IllegalStateException("cache is closed");
		}
	}

	/** Force buffered operations to the filesystem. */
	public synchronized void flush() throws IOException {
		checkNotClosed();
		trimToSize();
		trimToFileCount();
		journalWriter.flush();
	}

	/** Closes this cache. Stored values will remain on the filesystem. */
	public synchronized void close() throws IOException {
		if (journalWriter == null) {
			return; // Already closed.
		}
		for (Entry entry : new ArrayList<Entry>(lruEntries.values())) {
			if (entry.currentEditor != null) {
				entry.currentEditor.abort();
			}
		}
		trimToSize();
		trimToFileCount();
		journalWriter.close();
		journalWriter = null;
	}

	/**
	 * 保证缓存大小
	 * @throws IOException
	 */
	private void trimToSize() throws IOException {
		while (size > maxSize) {
			Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
			remove(toEvict.getKey());
		}
	}

	/**
	 * 保证 缓存文件数量
	 * @throws IOException
	 */
	private void trimToFileCount() throws IOException {
		while (fileCount > maxFileCount) {
			Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
			remove(toEvict.getKey());
		}
	}

	/**
	 * Closes the cache and deletes all of its stored values. This will delete
	 * all files in the cache directory including files that weren't created by
	 * the cache.
	 */
	public void delete() throws IOException {
		close();
		Util.deleteContents(directory);
	}

	private void validateKey(String key) {
		Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,64}: \"" + key + "\"");
		}
	}

	private static String inputStreamToString(InputStream in) throws IOException {
		return Util.readFully(new InputStreamReader(in, Util.UTF_8));
	}

	/** A snapshot of the values for an entry.
	 *
	 * 缓存文件的一个封装类
	 *
	 * */
	public final class Snapshot implements Closeable {
		private final String key;
		private final long sequenceNumber;
		private File[] files;
		private final InputStream[] ins;
		private final long[] lengths;

		private Snapshot(String key, long sequenceNumber, File[] files, InputStream[] ins, long[] lengths) {
			this.key = key;
			this.sequenceNumber = sequenceNumber;
			this.files = files;
			this.ins = ins;
			this.lengths = lengths;
		}

		/**
		 * Returns an editor for this snapshot's entry, or null if either the
		 * entry has changed since this snapshot was created or if another edit
		 * is in progress.
		 */
		public Editor edit() throws IOException {
			return DiskLruCache.this.edit(key, sequenceNumber);
		}

		/** Returns file with the value for {@code index}. */
		public File getFile(int index) {
			return files[index];
		}

		/** Returns the unbuffered stream with the value for {@code index}. */
		public InputStream getInputStream(int index) {
			return ins[index];
		}

		/** Returns the string value for {@code index}. */
		public String getString(int index) throws IOException {
			return inputStreamToString(getInputStream(index));
		}

		/** Returns the byte length of the value for {@code index}. */
		public long getLength(int index) {
			return lengths[index];
		}

		public void close() {
			for (InputStream in : ins) {
				Util.closeQuietly(in);
			}
		}
	}

	private static final OutputStream NULL_OUTPUT_STREAM = new OutputStream() {
		@Override
		public void write(int b) throws IOException {
			// Eat all writes silently. Nom nom.
		}
	};

	/** Edits the values for an entry.
	 *
	 * 编辑器
	 * */
	public final class Editor {
		// 对应的 实体独享
		private final Entry entry;

		// 是否读写数组
		private final boolean[] written;
		// 是否出错
		private boolean hasErrors;
		// 是否完成
		private boolean committed;

		private Editor(Entry entry) {
			this.entry = entry;
			this.written = (entry.readable) ? null : new boolean[valueCount];
		}

		/**
		 * Returns an unbuffered input stream to read the last committed value,
		 * or null if no value has been committed.
		 *
		 * 获取一个 没有缓冲的  getCleanFile 的输入流  读取流  没怎么用到
		 */
		public InputStream newInputStream(int index) throws IOException {
			synchronized (DiskLruCache.this) {
				if (entry.currentEditor != this) {
					throw new IllegalStateException();
				}
				if (!entry.readable) {
					return null;
				}
				try {
					return new FileInputStream(entry.getCleanFile(index));
				} catch (FileNotFoundException e) {
					return null;
				}
			}
		}

		/**
		 * Returns the last committed value as a string, or null if no value
		 * has been committed.
		 */
		public String getString(int index) throws IOException {
			InputStream in = newInputStream(index);
			return in != null ? inputStreamToString(in) : null;
		}

		/**
		 * Returns a new unbuffered output stream to write the value at
		 * {@code index}. If the underlying output stream encounters errors
		 * when writing to the filesystem, this edit will be aborted when
		 * {@link #commit} is called. The returned output stream does not throw
		 * IOExceptions.
		 *
		 * 获取 dirtyFile 输出流  即写文件流
		 *
		 * Bitmap  图片 都是谢大这个流 里面
		 *
		 */
		public OutputStream newOutputStream(int index) throws IOException {
			synchronized (DiskLruCache.this) {
				if (entry.currentEditor != this) {
					// entry 和编辑器 要和 自己匹配
					throw new IllegalStateException();
				}
				if (!entry.readable) {
					// 不能读, 那么就写
					written[index] = true;
				}
				// 获取 dirtyFile
				File dirtyFile = entry.getDirtyFile(index);
				FileOutputStream outputStream;
				try {
					outputStream = new FileOutputStream(dirtyFile);
				} catch (FileNotFoundException e) {
					// Attempt to recreate the cache directory.
					directory.mkdirs();
					try {
						outputStream = new FileOutputStream(dirtyFile);
					} catch (FileNotFoundException e2) {
						// We are unable to recover. Silently eat the writes.
						return NULL_OUTPUT_STREAM;
					}
				}
				return new FaultHidingOutputStream(outputStream);
			}
		}

		/** Sets the value at {@code index} to {@code value}.
		 *  把值 写到  dirtyFile 中
		 *  也么有哪里用到
		 * */
		public void set(int index, String value) throws IOException {
			Writer writer = null;
			try {
				writer = new OutputStreamWriter(newOutputStream(index), Util.UTF_8);
				writer.write(value);
			} finally {
				Util.closeQuietly(writer);
			}
		}

		/**
		 * Commits this edit so it is visible to readers.  This releases the
		 * edit lock so another edit may be started on the same key.
		 *
		 * 提交 释放编辑锁
		 */
		public void commit() throws IOException {
			if (hasErrors) {
				// 没错出 表示成功
				completeEdit(this, false);
				remove(entry.key); // The previous entry is stale.
			} else {
				completeEdit(this, true);
			}
			committed = true;
		}

		/**
		 * Aborts this edit. This releases the edit lock so another edit may be
		 * started on the same key.
		 * 写入失败 终止
		 */
		public void abort() throws IOException {
			completeEdit(this, false);
		}

		public void abortUnlessCommitted() {
			if (!committed) {
				try {
					abort();
				} catch (IOException ignored) {
				}
			}
		}

		/**
		 * FilterOutputStream 的包装 就是一旦出错了 不往外 抛 ,但是会自己 记录
		 */
		private class FaultHidingOutputStream extends FilterOutputStream {
			private FaultHidingOutputStream(OutputStream out) {
				super(out);
			}

			@Override public void write(int oneByte) {
				try {
					out.write(oneByte);
				} catch (IOException e) {
					hasErrors = true;
				}
			}

			@Override public void write(byte[] buffer, int offset, int length) {
				try {
					out.write(buffer, offset, length);
				} catch (IOException e) {
					hasErrors = true;
				}
			}

			@Override public void close() {
				try {
					out.close();
				} catch (IOException e) {
					hasErrors = true;
				}
			}

			@Override public void flush() {
				try {
					out.flush();
				} catch (IOException e) {
					hasErrors = true;
				}
			}
		}
	}

	/**
	 * LRL LiskHashMap 中对应的 实体
	 */
	private final class Entry {
		private final String key;

		/** Lengths of this entry's files.
		 *  文件的长度  大小
		 *
		 *  是个数组  有多个 ?
		 *  TODO
		 *  CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
		 *  后面是有两个数组 而且这些值 最后 会变成  lengths
		 *  两个都是 长度?
		 *
		 *
		 * */
		private final long[] lengths;

		/** True if this entry has ever been published.
		 *	默认是false  当 有 Entry 变为 CLEAN 的时候 他就为true了
		 *  表示是否刻度
		 *  挡在写文件的时候  他是不可读的
		 * */
		private boolean readable;

		/** The ongoing edit or null if this entry is not being edited.
		 *	如果为空 不是不在编辑
		 * 不为空 表示在编辑 那么 日志中的数据 就不能用了 是 	DIRTY
		 * */
		private Editor currentEditor;

		/** The sequence number of the most recently committed edit to this entry. */
		private long sequenceNumber;

		private Entry(String key) {
			this.key = key;
			this.lengths = new long[valueCount];
		}

		public String getLengths() throws IOException {
			StringBuilder result = new StringBuilder();
			for (long size : lengths) {
				result.append(' ').append(size);
			}
			return result.toString();
		}

		/** Set lengths using decimal numbers like "10123". */
		private void setLengths(String[] strings) throws IOException {
			if (strings.length != valueCount) {
				throw invalidLengths(strings);
			}

			try {
				for (int i = 0; i < strings.length; i++) {
					lengths[i] = Long.parseLong(strings[i]);
				}
			} catch (NumberFormatException e) {
				throw invalidLengths(strings);
			}
		}

		private IOException invalidLengths(String[] strings) throws IOException {
			throw new IOException("unexpected journal line: " + java.util.Arrays.toString(strings));
		}

		public File getCleanFile(int i) {
			return new File(directory, key + "." + i);
		}

		public File getDirtyFile(int i) {
			return new File(directory, key + "." + i + ".tmp");
		}
	}
}
