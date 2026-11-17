package app.kaizen.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El logo de una fuente sale de lo que declara su portada: apple-touch-icon primero, de mayor a menor tamaño, y nada que Android no pueda pintar. */
class SourceIconsTest {
    private val page = "https://fuente.example/inicio/"

    @Test
    fun `primero los apple-touch-icon grandes y luego los demas iconos`() {
        val html = """<html><head>
            <link rel="icon" type="image/png" sizes="32x32" href="/i/32.png">
            <link rel="apple-touch-icon" sizes="76x76" href="/t/76.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/t/180.png">
            <link rel="shortcut icon" sizes="192x192" href="https://cdn.example/192.png">
            </head></html>"""
        assertEquals(
            listOf("https://fuente.example/t/180.png", "https://fuente.example/t/76.png", "https://cdn.example/192.png"),
            SourceIcons.pick(html, page)
        )
    }

    @Test
    fun `se descartan los ico, los svg, lo que no es https y las repeticiones`() {
        val html = """<head>
            <link rel="icon" href="/favicon.ico">
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <link rel="icon" href="http://fuente.example/inseguro.png">
            <link rel="mask-icon" href="/mask.png">
            <link rel="icon" href="/a.png">
            <link rel="icon" href="/a.png#otra">
            </head>"""
        assertEquals(listOf("https://fuente.example/a.png"), SourceIcons.pick(html, page))
    }

    @Test
    fun `no se aceptan iconos de la red local ni de una IP`() {
        val html = """<head>
            <link rel="icon" href="https://203.0.113.5/a.png">
            <link rel="icon" href="https://localhost/b.png">
            <link rel="icon" href="https://impresora.local/c.png">
            <link rel="icon" href="https://[::1]/d.png">
            <link rel="icon" href="/ok.png">
            </head>"""
        assertEquals(listOf("https://fuente.example/ok.png"), SourceIcons.pick(html, page))
    }

    @Test
    fun `una portada sin iconos da una lista vacia y no mas de tres`() {
        assertTrue(SourceIcons.pick("<html><head><title>x</title></head></html>", page).isEmpty())
        val many = (1..6).joinToString("") { """<link rel="icon" sizes="${it * 10}x${it * 10}" href="/$it.png">""" }
        assertEquals(3, SourceIcons.pick("<head>$many</head>", page).size)
        assertEquals("https://fuente.example/6.png", SourceIcons.pick("<head>$many</head>", page).first())
    }
}
