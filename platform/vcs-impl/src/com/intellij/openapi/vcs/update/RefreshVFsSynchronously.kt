// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.update

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil.CASE_SENSITIVE_FILE_PATH_HASHING_STRATEGY
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.update.UpdateFilesHelper.iterateFileGroupFilesDeletedOnServerFirst
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

interface FilePathChange {
  val beforePath: FilePath?
  val afterPath: FilePath?
}

object RefreshVFsSynchronously {
  @JvmStatic
  fun updateAllChanged(updatedFiles: UpdatedFiles) {
    val collector = FilesCollector()
    iterateFileGroupFilesDeletedOnServerFirst(updatedFiles, collector)
    refreshDeletedFiles(collector.deletedFiles)
    refreshFiles(collector.files)
  }

  @JvmStatic
  fun refreshFiles(files: Collection<File>) {
    val toRefresh = files.mapNotNullTo(mutableSetOf()) { findValidParent(it) }
    markDirtyAndRefresh(false, false, false, *toRefresh.toTypedArray())
  }

  private fun refreshDeletedFiles(files: Collection<File>) {
    val toRefresh = files.mapNotNullTo(mutableSetOf()) { findValidParent(it.parentFile) }
    markDirtyAndRefresh(false, true, false, *toRefresh.toTypedArray())
  }

  private fun findValidParent(file: File?): VirtualFile? =
    generateSequence(file) { it.parentFile }
      .mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
      .firstOrNull { it.isValid }

  @JvmStatic
  fun updateChangesForRollback(changes: List<Change>) = refresh(changes, REVERSED_CHANGE_WRAPPER)

  @JvmStatic
  fun updateChanges(changes: Collection<Change>) = refresh(changes, CHANGE_WRAPPER)

  fun refresh(changes: Collection<FilePathChange>) = refresh(changes, FilePathChangeWrapper)

  private fun <T> refresh(changes: Collection<T>, wrapper: Wrapper<T>) {
    val files = mutableSetOf<File>()
    val deletedFiles = mutableSetOf<File>()
    changes.forEach { change ->
      val beforePath = wrapper.getBeforePath(change)
      val afterPath = wrapper.getAfterPath(change)

      beforePath?.let {
        (if (wrapper.isBeforePathDeleted(change)) deletedFiles else files) += it.ioFile
      }
      afterPath?.let {
        if (it != beforePath) files += it.ioFile
      }
    }
    refreshFiles(files)
    refreshDeletedFiles(deletedFiles)
  }
}

private val CHANGE_WRAPPER = ChangeWrapper(false)
private val REVERSED_CHANGE_WRAPPER = ChangeWrapper(true)

private class ChangeWrapper(private val isReversed: Boolean) : Wrapper<Change> {
  private fun getBeforeRevision(change: Change): ContentRevision? = change.run { if (isReversed) afterRevision else beforeRevision }
  private fun getAfterRevision(change: Change): ContentRevision? = change.run { if (isReversed) beforeRevision else afterRevision }

  override fun getBeforePath(change: Change): FilePath? = getBeforeRevision(change)?.file
  override fun getAfterPath(change: Change): FilePath? = getAfterRevision(change)?.file

  override fun isBeforePathDeleted(change: Change): Boolean =
    change.run { getAfterRevision(this) == null || isMoved || isRenamed || isIsReplaced }
}

object FilePathChangeWrapper : Wrapper<FilePathChange> {
  override fun getBeforePath(change: FilePathChange): FilePath? = change.beforePath
  override fun getAfterPath(change: FilePathChange): FilePath? = change.afterPath

  override fun isBeforePathDeleted(change: FilePathChange): Boolean =
    change.run { afterPath == null || !CASE_SENSITIVE_FILE_PATH_HASHING_STRATEGY.equals(beforePath, afterPath) }
}

private interface Wrapper<T> {
  fun getBeforePath(change: T): FilePath?
  fun getAfterPath(change: T): FilePath?
  fun isBeforePathDeleted(change: T): Boolean
}

private class FilesCollector : UpdateFilesHelper.Callback {
  val files = mutableSetOf<File>()
  val deletedFiles = mutableSetOf<File>()

  override fun onFile(filePath: String, groupId: String) {
    val file = File(filePath)
    if (FileGroup.REMOVED_FROM_REPOSITORY_ID == groupId || FileGroup.MERGED_WITH_TREE_CONFLICT.endsWith(groupId)) {
      deletedFiles.add(file)
    }
    else {
      files.add(file)
    }
  }
}