package app.zemote.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import app.zemote.BuildConfig

data class UpdateAsset(
    val abi: String,
    val fileName: String,
    val apkUrl: String,
    val md5Url: String? = null,
)

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    val body: String? = null,
    val assets: List<UpdateAsset> = emptyList(),
    val isNewer: Boolean,
    val isPrerelease: Boolean = false,
)

class UpdateCheckException(message: String) : IOException(message)

private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * Queries GitHub API for the latest release and compares with [currentVersion].
 */
suspend fun checkForUpdates(currentVersion: String = BuildConfig.VERSION_NAME): UpdateInfo {
    val endpoint = "https://api.github.com/repos/HumanAILoop/zemote/releases/latest"
    val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github.v3+json").build()
    val response = try {
        client.newCall(request).execute()
    } catch (e: Exception) {
        throw UpdateCheckException("网络请求失败: ${e.message}")
    }
    if (!response.isSuccessful) {
        throw UpdateCheckException("GitHub API ${response.code}: ${response.body?.string()?.take(200)}")
    }
    val body = response.body?.string() ?: throw UpdateCheckException("空响应")
    val gson = com.google.gson.Gson()
    @Suppress("UNCHECKED_CAST")
    val json = gson.fromJson(body, Map::class.java) as? Map<String, Any>
        ?: throw UpdateCheckException("无效响应格式")
    val tag = (json["tag_name"] as? String)?.removePrefix("v") ?: ""
    val releaseUrl = json["html_url"] as? String ?: "https://github.com/HumanAILoop/zemote/releases"
    val bodyText = json["body"] as? String
    val prerelease = json["prerelease"] as? Boolean == true
    @Suppress("UNCHECKED_CAST")
    val assetsJson = json["assets"] as? List<Map<String, Any>> ?: emptyList()

    val apkUrls = mutableMapOf<String, String>()
    val apkNames = mutableMapOf<String, String>()
    val md5Urls = mutableMapOf<String, String>()
    for (asset in assetsJson) {
        val name = asset["name"] as? String ?: continue
        val url = asset["browser_download_url"] as? String ?: continue
        val abi = abiFromAssetName(name) ?: continue
        when {
            name.endsWith(".apk") -> { apkUrls[abi] = url; apkNames[abi] = name }
            name.endsWith(".apk.md5") -> md5Urls[abi] = url
        }
    }
    val updateAssets = apkUrls.keys.map { abi ->
        UpdateAsset(
            abi = abi,
            fileName = apkNames[abi]!!,
            apkUrl = apkUrls[abi]!!,
            md5Url = md5Urls[abi],
        )
    }
    return UpdateInfo(
        latestVersion = tag,
        releaseUrl = releaseUrl,
        body = bodyText,
        assets = updateAssets,
        isNewer = tag.isNotEmpty() && compareVersions(tag, currentVersion) > 0,
        isPrerelease = prerelease,
    )
}

fun abiFromAssetName(name: String): String? {
    for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
        if (name.contains(abi)) return abi
    }
    return null
}

fun compareVersions(a: String, b: String): Int {
    val va = parseSemVer(a)
    val vb = parseSemVer(b)
    if (va.major != vb.major) return va.major.compareTo(vb.major)
    if (va.minor != vb.minor) return va.minor.compareTo(vb.minor)
    if (va.patch != vb.patch) return va.patch.compareTo(vb.patch)
    return 0
}

private fun parseSemVer(version: String): SemVer {
    val parts = version.split("+")[0].split("-")[0].split(".")
    return SemVer(
        major = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0,
        minor = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0,
        patch = parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
    )
}

private data class SemVer(val major: Int, val minor: Int, val patch: Int)
