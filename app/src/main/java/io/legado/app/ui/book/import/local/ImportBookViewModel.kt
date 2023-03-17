package io.legado.app.ui.book.import.local

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    val dataFlow = callbackFlow<List<FileDoc>> {

        val list = Collections.synchronizedList(ArrayList<FileDoc>())

        dataCallback = object : DataCallback {

            override fun setItems(fileDocs: List<FileDoc>) {
                list.clear()
                list.addAll(fileDocs)
                trySend(list)
            }

            override fun addItems(fileDocs: List<FileDoc>) {
                list.addAll(fileDocs)
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun screen(key: String?) {
                if (key.isNullOrBlank()) {
                    trySend(list)
                } else {
                    trySend(
                        list.filter { it.name.contains(key) }
                    )
                }
            }
        }

        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        when (sort) {
            2 -> docList.sortedWith(
                compareBy({ !it.isDir }, { -it.lastModified }, { it.name })
            )
            1 -> docList.sortedWith(
                compareBy({ !it.isDir }, { -it.size }, { it.name })
            )
            else -> docList.sortedWith(
                compareBy({ !it.isDir }, { it.name })
            )
        }
    }.flowOn(IO)

    fun addToBookshelf(uriList: HashSet<String>, finally: () -> Unit) {
        execute {
            val fileUris = uriList.map {
                Uri.parse(it)
            }
            LocalBook.importFiles(fileUris)
        }.onError {
            context.toastOnUi("添加书架失败，请尝试重新选择文件夹")
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onFinally {
            finally.invoke()
        }
    }

    fun addArchiveToBookShelf(
        fileDoc: FileDoc,
        fileName: String,
        onSuccess: (String) -> Unit
    ) {
        LocalBook.importArchiveFile(fileDoc.uri, fileName) {
            it.contains(fileName)
        }.firstOrNull()?.run {
            onSuccess.invoke(bookUrl)
        }
    }

    fun deleteDoc(uriList: HashSet<String>, finally: () -> Unit) {
        execute {
            uriList.forEach {
                val uri = Uri.parse(it)
                if (uri.isContentScheme()) {
                    DocumentFile.fromSingleUri(context, uri)?.delete()
                } else {
                    uri.path?.let { path ->
                        File(path).delete()
                    }
                }
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDir -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    fun scanDoc(
        fileDoc: FileDoc,
        isRoot: Boolean,
        scope: CoroutineScope,
        finally: (() -> Unit)? = null
    ) {
        if (isRoot) {
            dataCallback?.clear()
        }
        if (!scope.isActive) {
            finally?.invoke()
            return
        }
        kotlin.runCatching {
            val list = ArrayList<FileDoc>()
            fileDoc.list()!!.forEach { docItem ->
                if (!scope.isActive) {
                    finally?.invoke()
                    return
                }
                if (docItem.isDir) {
                    scanDoc(docItem, false, scope)
                } else if (docItem.name.matches(bookFileRegex) || docItem.name.matches(archiveFileRegex)
                ) {
                    list.add(docItem)
                }
            }
            if (!scope.isActive) {
                finally?.invoke()
                return
            }
            if (list.isNotEmpty()) {
                dataCallback?.addItems(list)
            }
        }.onFailure {
            context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
        }
        if (isRoot) {
            finally?.invoke()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
       dataCallback?.screen(filterKey)
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun screen(key: String?)

    }

}