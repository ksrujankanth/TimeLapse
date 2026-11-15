package com.dan.timelapse.utils

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log


class UriFile(
    val context: Context,
    val uri: Uri,
    val name: String,
    val mime: String,
    private val documentId: String
) {

    companion object {
        private const val TAG = "UriFile"
        private val DOCUMENT_ID_COLUMNS = listOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            "_id"
        )

        private fun getColumnIndex( cursor: Cursor, columnNames: List<String> ): Int {
            for (columnName in columnNames) {
                val index = cursor.getColumnIndex(columnName)
                if (index >= 0) return index
            }
            return -1
        }

        private fun queryUri( context: Context, uriQuery: Uri, uri: Uri, onlyFirstRecord: Boolean, isTreeUri: Boolean ): List<UriFile> {
            val result = mutableListOf<UriFile>()
            var cursor: Cursor? = null

            try {
                cursor = context.contentResolver.query(uriQuery, null, null, null, null)
                if (null != cursor) {
                    val indexDocumentId = getColumnIndex(cursor, DOCUMENT_ID_COLUMNS)
                    val indexDisplayName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val indexMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    if (indexDocumentId >= 0 && indexDisplayName >= 0 && indexMime >= 0) {
                        while (cursor.moveToNext()) {
                            val documentId = cursor.getString(indexDocumentId)
                            val mime = cursor.getString(indexMime)

                            result.add(
                                UriFile(
                                    context,
                                    if (isTreeUri) DocumentsContract.buildDocumentUriUsingTree(uri, documentId) else uri,
                                    cursor.getString(indexDisplayName),
                                    mime,
                                    documentId
                                )
                            )

                            if (onlyFirstRecord) break
                        }
                    }
                }
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Failed to query content resolver for URI: $uriQuery", e)
                // This can happen on some devices when querying a tree URI, return empty list
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query content resolver for URI: $uriQuery", e)
            }

            cursor?.close()

            return result.toList()
        }

        private fun fromTreeDocumentId( context: Context, treeUri: Uri, documentId: String ): UriFile? {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val list = queryUri(context, uri, uri, onlyFirstRecord = true, isTreeUri = true)
            return if (list.isNotEmpty()) list[0] else null
        }

        fun fromTreeUri(context: Context, uri: Uri): UriFile? {
            return try {
                val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.getDocumentId(uri)
                } else {
                    DocumentsContract.getTreeDocumentId(uri)
                }
                
                // MANUALLY build the UriFile for the folder to avoid the Unsupported query crash
                UriFile(
                    context = context,
                    uri = uri,
                    name = "Selected Folder", 
                    mime = DocumentsContract.Document.MIME_TYPE_DIR,
                    documentId = documentId
                )
            } catch (e: Exception) {
                Log.e("UriFile", "fromTreeUri failed for URI: $uri", e)
                null
            }
        }

        fun fromSingleUri(context: Context, uri: Uri): UriFile? {
            try {
                val list = queryUri(context, uri, uri, onlyFirstRecord = true, isTreeUri = false)
                return if (list.isNotEmpty()) list[0] else null
            } catch (e: Exception) {
                Log.e(TAG, "fromSingleUri failed for URI: $uri", e)
            }

            return null
        }
    }

    val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mime
    val isPhoto = mime.startsWith("image/")
    val isVideo = mime.startsWith("video/")

    fun listFiles(): List<UriFile> {
        if (!isDirectory) return emptyList()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            return queryUri(context, childrenUri, uri, onlyFirstRecord = false, isTreeUri = true)
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed for URI: $uri", e)
        }

        return emptyList()
    }
}