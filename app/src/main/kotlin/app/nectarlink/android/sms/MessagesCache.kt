// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.sms

import android.content.Context
import android.util.Base64
import android.util.Log
import app.nectarlink.android.core.Core
import app.nectarlink.core.SmsMessage
import app.nectarlink.core.SmsPart
import app.nectarlink.core.SmsThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance dual-tier (Memory + Disk) cache for SMS threads and messages.
 * Keeps conversations in memory for 120 FPS buttery-smooth scrolling and zero network lag.
 */
class MessagesCache private constructor(private val context: Context) {

    // Tier 1: In-Memory cache for instantaneous UI access
    private val memoryThreads = ConcurrentHashMap<String, List<SmsThread>>()
    private val memoryMessages = ConcurrentHashMap<String, List<SmsMessage>>()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "sms_cache").apply { mkdirs() }
    }

    /** Returns cached threads for a peer from memory or disk, or empty if not yet cached. */
    fun getCachedThreads(peerId: String): List<SmsThread> {
        memoryThreads[peerId]?.let { return it }
        val disk = loadThreadsFromDisk(peerId)
        if (disk.isNotEmpty()) {
            memoryThreads[peerId] = disk
        }
        return disk
    }

    /** Stores fresh threads in memory and persists asynchronously to disk. */
    fun putThreads(peerId: String, threads: List<SmsThread>) {
        memoryThreads[peerId] = threads
        saveThreadsToDisk(peerId, threads)
    }

    /** Clears unread count for a thread in memory and disk cache. */
    fun markThreadRead(peerId: String, threadId: String) {
        val current = memoryThreads[peerId] ?: loadThreadsFromDisk(peerId)
        if (current.isNotEmpty()) {
            val updated = current.map {
                if (it.id == threadId) it.copy(unread = 0u) else it
            }
            putThreads(peerId, updated)
        }
    }

    /** Returns cached messages for a thread from memory or disk, or empty if not yet cached. */
    fun getCachedMessages(peerId: String, threadId: String): List<SmsMessage> {
        val key = cacheKey(peerId, threadId)
        memoryMessages[key]?.let { return it }
        val disk = loadMessagesFromDisk(peerId, threadId)
        if (disk.isNotEmpty()) {
            memoryMessages[key] = disk
        }
        return disk
    }

    /** Stores messages in memory and persists asynchronously to disk. */
    fun putMessages(peerId: String, threadId: String, msgs: List<SmsMessage>) {
        val key = cacheKey(peerId, threadId)
        memoryMessages[key] = msgs
        saveMessagesToDisk(peerId, threadId, msgs)
    }

    /** Adds an optimistic outgoing message directly to memory cache for zero UI delay. */
    fun addOptimisticOutgoing(peerId: String, threadId: String, recipient: String, body: String): SmsMessage {
        val key = cacheKey(peerId, threadId)
        val now = System.currentTimeMillis()
        val optimisticMsg = SmsMessage(
            id = "pending_${now}",
            thread = threadId,
            address = recipient,
            body = body,
            date = now,
            outgoing = true,
            status = "sending",
            parts = emptyList(),
        )
        val current = memoryMessages[key]?.toMutableList() ?: mutableListOf()
        current.add(optimisticMsg)
        memoryMessages[key] = current
        saveMessagesToDisk(peerId, threadId, current)
        return optimisticMsg
    }

    /** Preloads messages for top threads in the background to ensure instant viewing. */
    fun preloadTopThreads(scope: CoroutineScope, core: Core, peerId: String, threads: List<SmsThread>, limit: Int = 8) {
        scope.launch(Dispatchers.IO) {
            val top = threads.take(limit)
            for (th in top) {
                val key = cacheKey(peerId, th.id)
                if (!memoryMessages.containsKey(key)) {
                    runCatching {
                        val msgs = core.peerSmsMessages(peerId, th.id)
                        if (msgs.isNotEmpty()) {
                            putMessages(peerId, th.id, msgs.reversed())
                        }
                    }
                }
            }
        }
    }

    private fun cacheKey(peerId: String, threadId: String): String = "${peerId}_$threadId"

    private fun loadThreadsFromDisk(peerId: String): List<SmsThread> {
        return try {
            val file = File(cacheDir, "threads_${peerId}.json")
            if (!file.exists()) return emptyList()
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = ArrayList<SmsThread>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val addressesArr = obj.getJSONArray("addresses")
                val addresses = (0 until addressesArr.length()).map { addressesArr.getString(it) }
                val namesArr = obj.getJSONArray("names")
                val names = (0 until namesArr.length()).map { namesArr.getString(it) }
                val photoBase64 = obj.optString("photo", null)
                val photo = if (!photoBase64.isNullOrEmpty()) Base64.decode(photoBase64, Base64.DEFAULT) else null
                list.add(
                    SmsThread(
                        id = obj.getString("id"),
                        addresses = addresses,
                        names = names,
                        snippet = obj.optString("snippet", ""),
                        date = obj.getLong("date"),
                        unread = obj.optInt("unread", 0).toUInt(),
                        photo = photo,
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached threads for $peerId", e)
            emptyList()
        }
    }

    private fun saveThreadsToDisk(peerId: String, threads: List<SmsThread>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val array = JSONArray()
                for (th in threads) {
                    val obj = JSONObject()
                    obj.put("id", th.id)
                    obj.put("addresses", JSONArray(th.addresses))
                    obj.put("names", JSONArray(th.names))
                    obj.put("snippet", th.snippet)
                    obj.put("date", th.date)
                    obj.put("unread", th.unread.toInt())
                    th.photo?.let {
                        obj.put("photo", Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                    array.put(obj)
                }
                val file = File(cacheDir, "threads_${peerId}.json")
                file.writeText(array.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist threads for $peerId", e)
            }
        }
    }

    private fun loadMessagesFromDisk(peerId: String, threadId: String): List<SmsMessage> {
        return try {
            val file = File(cacheDir, "msgs_${peerId}_${threadId}.json")
            if (!file.exists()) return emptyList()
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = ArrayList<SmsMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SmsMessage(
                        id = obj.getString("id"),
                        thread = obj.getString("thread"),
                        address = obj.getString("address"),
                        body = obj.getString("body"),
                        date = obj.getLong("date"),
                        outgoing = obj.getBoolean("outgoing"),
                        status = obj.optString("status", null),
                        parts = emptyList(),
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached messages for $peerId:$threadId", e)
            emptyList()
        }
    }

    private fun saveMessagesToDisk(peerId: String, threadId: String, msgs: List<SmsMessage>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val array = JSONArray()
                for (m in msgs) {
                    val obj = JSONObject()
                    obj.put("id", m.id)
                    obj.put("thread", m.thread)
                    obj.put("address", m.address)
                    obj.put("body", m.body)
                    obj.put("date", m.date)
                    obj.put("outgoing", m.outgoing)
                    m.status?.let { obj.put("status", it) }
                    array.put(obj)
                }
                val file = File(cacheDir, "msgs_${peerId}_${threadId}.json")
                file.writeText(array.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist messages for $peerId:$threadId", e)
            }
        }
    }

    companion object {
        private const val TAG = "MessagesCache"
        @Volatile private var instance: MessagesCache? = null

        fun get(context: Context): MessagesCache =
            instance ?: synchronized(this) {
                instance ?: MessagesCache(context.applicationContext).also { instance = it }
            }
    }
}
