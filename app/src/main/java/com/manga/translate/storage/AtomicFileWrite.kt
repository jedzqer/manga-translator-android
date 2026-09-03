package com.manga.translate.storage

import java.io.File

/**
 * 原子写入：先写同目录临时文件，再 rename 到目标。
 *
 * rename 失败时先删除已存在的目标再重试一次；仍失败则回退为直接写入目标文件，
 * 保证内容落盘但失去原子性。返回是否通过 rename 原子完成（回退路径返回 false）。
 * IO 异常不在此处吞掉，由调用方按各自原有行为处理。
 */
internal fun writeFileAtomically(file: File, text: String): Boolean {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    try {
        tmp.writeText(text)
        if (tmp.renameTo(file)) return true
        if (file.exists()) file.delete()
        if (tmp.renameTo(file)) return true
        file.writeText(text)
        return false
    } finally {
        tmp.delete()
    }
}
