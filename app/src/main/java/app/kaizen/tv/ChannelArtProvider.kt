package app.kaizen.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Sirve al launcher de la TV las miniaturas de «Capítulos recientes» y «Seguir viendo» desde la caché de disco de las imágenes (ver [ChannelArt]).
 * Solo lectura y solo de lo publicado en una fila; no consulta ni descarga nada: lo que no está guardado es «no encontrado».
 */
class ChannelArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Solo lectura")
        val context = context ?: throw FileNotFoundException()
        val url = ChannelArt.urlOf(uri) ?: throw FileNotFoundException()
        val file = ChannelArt.cachedFile(context, url) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Solo se sirven ficheros: no hay tablas que consultar ni nada que escribir.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
