package nl.amony.modules.search.solr

/** Schema shared by the Solr indexer and the search service: the collection name and the document field names. */
object SolrSchema:

  val collectionName = "resources"

  object FieldNames {
    val id                 = "id"
    val bucketId           = "bucket_id_s"
    val partialHash        = "partial_hash_s"
    val path               = "path_text_ci"
    val filesize           = "filesize_l"
    val tags               = "tags_ss"
    val thumbnailTimestamp = "thumbnailtimestamp_i"
    val title              = "title_s"
    val videoCodec         = "video_codec_s"
    val description        = "description_s"
    val metaToolName       = "meta_tool_name_s"
    val timeAdded          = "time_added_l"
    val lastModified       = "time_last_modified_l"
    val contentType        = "content_type_s"
    val width              = "width_i"
    val height             = "height_i"
    val duration           = "duration_i"
    val fps                = "fps_f"
    val resourceType       = "resource_type_s"

    // TODO: the Solr schema has no boolean dynamic field, so streamability is stored as a string
    //  ("true"/"false") and an absent field means unknown. Revisit if a *_b dynamic field is added.
    val streamable = "streamable_s"
    val userId     = "user_id_s"
  }
