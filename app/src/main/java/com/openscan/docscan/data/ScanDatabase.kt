package com.openscan.docscan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 一份扫描文档（可含多页）。 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 文档中的一页。图片以文件形式存放，数据库只保存路径。 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** 相对 app 私有目录的文件名 */
    val fileName: String,
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val enhanceMode: String,
    val upscaleScale: Int
)

@Dao
interface ScanDao {

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex ASC")
    fun observePages(docId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY pageIndex ASC")
    suspend fun getPages(docId: Long): List<PageEntity>

    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: PageEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("UPDATE documents SET title = :title, updatedAt = :ts WHERE id = :id")
    suspend fun renameDocument(id: Long, title: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET updatedAt = :ts WHERE id = :id")
    suspend fun touchDocument(id: Long, ts: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteDocument(doc: DocumentEntity)

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deletePage(pageId: Long)

    /** 删除文档并返回其所有页，便于调用方同步清理磁盘文件。 */
    @Transaction
    suspend fun deleteDocumentWithPages(doc: DocumentEntity): List<PageEntity> {
        val pages = getPages(doc.id)
        deleteDocument(doc)
        return pages
    }

    /** 重排页码，用于删除中间页后修正顺序。 */
    @Transaction
    suspend fun reindexPages(docId: Long) {
        val pages = getPages(docId)
        pages.forEachIndexed { index, page ->
            if (page.pageIndex != index) {
                insertPage(page.copy(pageIndex = index))
            }
        }
    }
}

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScanDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var INSTANCE: ScanDatabase? = null

        fun get(context: Context): ScanDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScanDatabase::class.java,
                    "docscan.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
