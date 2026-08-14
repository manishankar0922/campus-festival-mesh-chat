package com.bitchat.android.hotspot

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Lightweight HTTP server for serving the universal APK over Wi-Fi P2P hotspot.
 * Based on NanoHTTPD.
 */
class ApkWebServer(
    private val context: Context,
    private val apkFile: File,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApkWebServer"
        const val DEFAULT_PORT = 9999
    }

    private val appVersion: String by lazy {
        try {
            context.packageManager
                .getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?.versionName
                ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // Cache the HTML landing page (generated once, reused for all requests)
    private val cachedHtml: String by lazy {
        generateLandingPageHtml()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        Log.d(TAG, "Request: ${session.method} $uri from ${session.remoteIpAddress}")

        return when {
            uri == "/bitchat.apk" -> {
                serveApk()
            }
            uri == "/favicon.ico" -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            else -> {
                serveLandingPage()
            }
        }
    }

    /**
     * Serve the APK file.
     */
    private fun serveApk(): Response {
        return try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.path}")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "APK file not found"
                )
            }

            Log.d(TAG, "Serving APK: ${apkFile.name} (${apkFile.length() / 1024 / 1024}MB)")

            val inputStream = FileInputStream(apkFile)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.android.package-archive",
                inputStream,
                apkFile.length()
            )

            response.addHeader("Content-Disposition", "attachment; filename=\"bitchat-${appVersion}.apk\"")
            response.addHeader("Accept-Ranges", "bytes")

            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving APK", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error serving APK: ${e.message}"
            )
        }
    }

    /**
     * Serve the HTML landing page.
     */
    private fun serveLandingPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            cachedHtml
        )
    }

    /**
     * Generate HTML landing page.
     */
    private fun generateLandingPageHtml(): String {
        val apkSizeMb = apkFile.length() / 1024 / 1024

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>Download BluChat</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
            background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .container {
            background: rgba(255, 255, 255, 0.95);
            backdrop-filter: blur(10px);
            border-radius: 24px;
            padding: 40px 30px;
            max-width: 400px;
            width: 100%;
            text-align: center;
            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.2);
        }

        .logo {
            font-size: 64px;
            margin-bottom: 20px;
        }

        h1 {
            color: #1a202c;
            font-size: 28px;
            margin-bottom: 8px;
            font-weight: 700;
        }

        .subtitle {
            color: #718096;
            font-size: 14px;
            margin-bottom: 30px;
        }

        .info-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 15px;
            margin-bottom: 30px;
        }

        .info-box {
            background: #f7fafc;
            border: 1px solid #e2e8f0;
            border-radius: 12px;
            padding: 15px;
        }

        .info-label {
            font-size: 12px;
            color: #a0aec0;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 5px;
        }

        .info-value {
            font-size: 16px;
            color: #2d3748;
            font-weight: 600;
        }

        .download-button {
            display: block;
            width: 100%;
            padding: 18px;
            background: #0066FF;
            color: white;
            text-decoration: none;
            border-radius: 16px;
            font-size: 18px;
            font-weight: 700;
            margin-bottom: 30px;
            box-shadow: 0 10px 20px rgba(0, 102, 255, 0.3);
            transition: transform 0.2s, box-shadow 0.2s;
        }

        .download-button:active {
            transform: scale(0.98);
        }

        .instructions {
            text-align: left;
            background: #f7fafc;
            border-radius: 16px;
            padding: 20px;
            margin-bottom: 20px;
        }

        .instructions h3 {
            font-size: 14px;
            color: #2d3748;
            margin-bottom: 12px;
        }

        .instructions ol {
            padding-left: 20px;
            color: #4a5568;
            font-size: 13px;
            line-height: 1.6;
        }

        .warning {
            background: #fffaf0;
            border: 1px solid #feebc8;
            border-radius: 12px;
            padding: 15px;
            color: #742a2a;
            font-size: 12px;
            line-height: 1.5;
            text-align: left;
        }

        .warning strong {
            display: block;
            margin-bottom: 5px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo">💬</div>
        <h1>BluChat</h1>
        <p class="subtitle">Offline Campus Festival Communication</p>

        <div class="info-grid">
            <div class="info-box">
                <div class="info-label">Version</div>
                <div class="info-value">$appVersion</div>
            </div>
            <div class="info-box">
                <div class="info-label">Size</div>
                <div class="info-value">${apkSizeMb} MB</div>
            </div>
        </div>

        <a href="/bitchat.apk" class="download-button">
            📥 Download BluChat
        </a>

        <div class="instructions">
            <h3>📱 Installation Instructions</h3>
            <ol>
                <li>Tap the download button above</li>
                <li>Wait for the download to complete</li>
                <li>Open the downloaded APK file</li>
                <li>If prompted, enable "Install from unknown sources"</li>
                <li>Follow the installation prompts</li>
            </ol>
        </div>

        <div class="warning">
            <strong>⚠️ Note:</strong>
            If you already have BluChat installed, you may need to uninstall it first before installing this version. Make sure to backup your data if needed.
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Start the server.
     */
    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Web server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            throw e
        }
    }

    /**
     * Stop the server.
     */
    fun stopServer() {
        try {
            stop()
            Log.d(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }
    }
}
