package com.manga.translate.storage

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 原子写入：先写同目录临时文件，再 rename 到目标。
 *
 * 每个规范化目标路径串行提交，临时文件使用唯一名称。rename 失败时保留旧目标并抛出异常。
 * IO 异常不在此处吞掉，由调用方按各自原有行为处理。
 */
private val fileWriteLocks = ConcurrentHashMap<String, Mutex>()

internal fun writeFileAtomically(file: File, text: String): Boolean {
    val target = file.absoluteFile
    val lock = fileWriteLocks.computeIfAbsent(target.path) { Mutex() }
    return kotlinx.coroutines.runBlocking {
        lock.withLock {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw java.io.IOException("Unable to create parent directory for $target")
            }
            val tmp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    throw java.io.IOException("Unable to atomically replace $target")
                }
                true
            } finally {
                tmp.delete()
            }
        }
    }
}
