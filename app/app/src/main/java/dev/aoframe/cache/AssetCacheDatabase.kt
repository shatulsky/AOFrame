package dev.aoframe.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.aoframe.immich.ImmichAsset
import dev.aoframe.immich.ImmichAssetType

private const val DB_NAME = "asset-cache.db"
private const val DB_VERSION = 2

// Bounds how many syncs a "no face found" result stays retryable before
// it's treated as final. A brand-new asset is synced (via the Immich
// upload webhook, immediately) before Immich's own
// async ML face-detection job has necessarily finished on it, so a single
// null result is not trustworthy evidence "this photo has no face" - it
// may just mean "not analyzed yet". Retrying across the next few syncs
// (subsequent webhooks, or the periodic 30-minute catch-all in
// MainActivity) lets a late-arriving face target still get picked up,
// while still capping the cost for photos that genuinely have no face
// (loadKnownFaceTargets() stops surfacing them as "needs querying" once
// this cap is hit).
private const val MAX_FACE_QUERY_ATTEMPTS = 3

/**
 * Plain SQLiteOpenHelper, not Room - a few thousand rows max with a
 * single-owner schema, not worth Room's dependency/generated-code
 * footprint at this scale.
 *
 * Two tables, deliberately separate concerns:
 * - `assets`: what Immich says exists right now, plus our own computed
 *   face-target (cached permanently here so a re-sync doesn't re-query
 *   faces for assets already seen - see AssetRepository's
 *   knownFaceTargets parameter).
 * - `cached_files`: which bytes (thumbnail/backdrop/video) are actually
 *   downloaded to local disk for a given asset, and where.
 */
class AssetCacheDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE assets (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                video_id TEXT,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                face_x REAL,
                face_y REAL,
                has_face_target INTEGER NOT NULL,
                face_query_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE cached_files (
                asset_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                local_path TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                PRIMARY KEY (asset_id, kind)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS assets")
        db.execSQL("DROP TABLE IF EXISTS cached_files")
        onCreate(db)
    }

    // present-but-null means "queried, no face found, retries exhausted"
    // (final - valid cached knowledge, not re-queried); absent means
    // "never queried, or queried but still within its retry budget" -
    // both cases AssetRepository should (re)query. See
    // MAX_FACE_QUERY_ATTEMPTS's comment above for why a null result isn't
    // final on its own.
    fun loadKnownFaceTargets(): Map<String, Pair<Float, Float>?> {
        val result = mutableMapOf<String, Pair<Float, Float>?>()
        readableDatabase.rawQuery(
            "SELECT id, face_x, face_y, face_query_count FROM assets WHERE has_face_target = 1",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val hasCoords = !cursor.isNull(1) && !cursor.isNull(2)
                val queryCount = cursor.getInt(3)
                if (hasCoords) {
                    result[id] = cursor.getFloat(1) to cursor.getFloat(2)
                } else if (queryCount >= MAX_FACE_QUERY_ATTEMPTS) {
                    result[id] = null
                }
                // else: still pending - leave out of the map entirely.
            }
        }
        return result
    }

    // Ids present in the manifest but not in `freshAssets` - trashed/
    // archived/deleted upstream since the last sync. Read-only; callers
    // should look up and delete these assets' cached files (see
    // cachedFilesForAsset()) *before* calling replaceAssets(), which is
    // what actually removes their DB rows.
    fun computeStaleAssetIds(freshAssets: List<ImmichAsset>): List<String> =
        staleAssetIds(readableDatabase, freshAssets.map { it.id }.toSet())

    // Upserts the fresh asset list's metadata, then deletes anything no
    // longer present - both the `assets` row and any `cached_files` rows
    // naming it, keeping the manifest in sync with the live library.
    fun replaceAssets(assets: List<ImmichAsset>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // CONFLICT_REPLACE fully replaces a row rather than updating
            // it in place, so face_query_count has to be read out and
            // carried forward explicitly here - otherwise it would reset
            // to 0 on every sync and the retry cap in
            // loadKnownFaceTargets() would never actually bind.
            val existingCounts = mutableMapOf<String, Int>()
            db.rawQuery("SELECT id, face_query_count FROM assets", null).use { cursor ->
                while (cursor.moveToNext()) existingCounts[cursor.getString(0)] = cursor.getInt(1)
            }

            for (asset in assets) {
                val hasFaceTarget = asset.type == ImmichAssetType.IMAGE
                val queryCount = when {
                    !hasFaceTarget -> 0
                    asset.faceX != null -> existingCounts[asset.id] ?: 0
                    else -> minOf((existingCounts[asset.id] ?: 0) + 1, MAX_FACE_QUERY_ATTEMPTS)
                }
                val values = ContentValues().apply {
                    put("id", asset.id)
                    put("type", asset.type.name)
                    put("video_id", asset.videoId)
                    put("width", asset.width)
                    put("height", asset.height)
                    put("face_x", asset.faceX)
                    put("face_y", asset.faceY)
                    // Only IMAGE assets are ever face-queried (videos/
                    // Live Photos have nothing to target) - so this flag
                    // only ever matters, and is only ever read, for
                    // IMAGE ids (see loadKnownFaceTargets() and
                    // AssetRepository's knownFaceTargets parameter).
                    put("has_face_target", if (hasFaceTarget) 1 else 0)
                    put("face_query_count", queryCount)
                }
                db.insertWithOnConflict("assets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            val freshIds = assets.map { it.id }.toSet()
            for (id in staleAssetIds(db, freshIds)) {
                db.delete("assets", "id = ?", arrayOf(id))
                db.delete("cached_files", "asset_id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cachedFilePath(assetId: String, kind: String): String? {
        readableDatabase.rawQuery(
            "SELECT local_path FROM cached_files WHERE asset_id = ? AND kind = ?",
            arrayOf(assetId, kind)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun recordCachedFile(assetId: String, kind: String, localPath: String, byteSize: Long) {
        val values = ContentValues().apply {
            put("asset_id", assetId)
            put("kind", kind)
            put("local_path", localPath)
            put("byte_size", byteSize)
        }
        writableDatabase.insertWithOnConflict("cached_files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Backing GET /status on the local control server - real numbers
    // from the manifest, not estimates.
    fun assetCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM assets", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    // Backing GET /status's per-type breakdown (see MainActivity.buildStatusJson) -
    // assetCount() alone can't tell an admin how many of the library are
    // videos vs. photos, which /frameo/settings needs to show separately.
    fun countsByType(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT type, COUNT(*) FROM assets GROUP BY type", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
        }
        return counts
    }

    // Backing GET /status's face-detection breakdown (see
    // MainActivity.buildStatusJson) - lets /frameo/settings show how much
    // of the library has a resolved face target vs. is still working
    // through its retry budget vs. genuinely has no qualifying face. Same
    // three-way split loadKnownFaceTargets() reasons about, just counted
    // instead of resolved into a lookup map.
    data class FaceQueryStats(val found: Int, val none: Int, val pending: Int)

    fun faceQueryStats(): FaceQueryStats {
        var found = 0
        var none = 0
        var pending = 0
        readableDatabase.rawQuery(
            "SELECT face_x, face_y, face_query_count FROM assets WHERE has_face_target = 1",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val hasCoords = !cursor.isNull(0) && !cursor.isNull(1)
                val queryCount = cursor.getInt(2)
                when {
                    hasCoords -> found++
                    queryCount >= MAX_FACE_QUERY_ATTEMPTS -> none++
                    else -> pending++
                }
            }
        }
        return FaceQueryStats(found, none, pending)
    }

    // Backs the "reset faces cache" admin action (/frameo/settings) -
    // needed whenever the face-targeting heuristic changes (e.g. union
    // bounding box, dropping small/background faces), since there'd
    // otherwise be no way to force already-resolved assets to be
    // re-queried under the new rules short of clearing the app's data
    // entirely. Clears every IMAGE asset back to "never queried" so the next sync
    // re-runs fetchFaceTarget() for the whole library - see
    // AssetRepository.refreshAssets()'s knownFaceTargets handling.
    fun resetFaceQueries() {
        writableDatabase.execSQL(
            "UPDATE assets SET face_x = NULL, face_y = NULL, face_query_count = 0 WHERE has_face_target = 1"
        )
    }

    fun totalCachedBytes(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(byte_size), 0) FROM cached_files", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    fun cachedFilesForAsset(assetId: String): List<String> {
        val paths = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT local_path FROM cached_files WHERE asset_id = ?",
            arrayOf(assetId)
        ).use { cursor ->
            while (cursor.moveToNext()) paths.add(cursor.getString(0))
        }
        return paths
    }

    private fun staleAssetIds(db: SQLiteDatabase, freshIds: Set<String>): List<String> {
        val stale = mutableListOf<String>()
        db.rawQuery("SELECT id FROM assets", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in freshIds) stale.add(id)
            }
        }
        return stale
    }
}
