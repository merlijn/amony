package nl.amony.service.resources.database

import io.circe.Json
import scribe.Logging
import skunk.*
import skunk.circe.codec.all.*
import skunk.implicits.*
import skunk.codec.all.*
import skunk.data.Arr

object Queries extends Logging {
  
  object tags {
      val all: Query[Void, TagRow] = sql"select id, label from tags".query(TagRow.codec)

      def truncateCascade: Command[Void] = sql"truncate table tags cascade".command

      def getByLabels(n: Int): Query[List[String], TagRow] =
        sql"select id,label from tags where label in (${varchar.list(n)})".query(TagRow.codec)

      def getByIds(n: Int): Query[List[Int], TagRow] =
        sql"select id,label from tags where id in (${int4.list(n)})".query(TagRow.codec)

      def insert(n: Int): Command[List[String]] =
        sql"insert into tags (label) values ${varchar.values.list(n)}".command

      def upsertSql(n: Int): Command[List[String]] =
        sql"insert into tags (label) values ${varchar.values.list(n)} on conflict (label) do nothing".command

      def upsert(n: Int): Command[List[String]] =
        sql"""
          WITH new_tags (label) AS (VALUES ${varchar.values.list(n)})
          INSERT INTO tags (label)
          SELECT label
          FROM new_tags
          WHERE NOT EXISTS (SELECT 1 FROM tags WHERE tags.label = new_tags.label)
        """.command
  }
  
  object resource_tags {

    val truncateCascade: Command[Void] = sql"truncate table resource_tags cascade".command
    
    def upsert(n: Int): Command[List[ResourceTagsRow]] =
      sql"insert into resource_tags (bucket_id, resource_id, tag_id) values ${ResourceTagsRow.codec.values.list(n)} on conflict (bucket_id, resource_id, tag_id) do nothing".command

    val getById: Query[(String, String), ResourceTagsRow] =
      sql"select bucket_id, resource_id, tag_id from resource_tags where bucket_id = ${varchar(64)} and resource_id = ${varchar(64)}".query(ResourceTagsRow.codec)

    def delete: Command[(String, String)] =
      sql"delete from resource_tags where bucket_id = $varchar and resource_id = $varchar".command
  }

  object resources {
    
    val truncateCascade: Command[Void] = sql"truncate table resources cascade".command
    
    val deleteBucket: Command[(String, String)] =
      sql"delete from resources where bucket_id = $varchar and resource_id = $varchar".command

    val allJoined: Query[String, (ResourceRow, Option[Arr[String]])] =
      sql"""
       SELECT to_json(r.*), array_agg(t.label) FILTER (WHERE t.label IS NOT NULL) as tags
       FROM resources r
       LEFT JOIN resource_tags rt
         ON r.bucket_id = rt.bucket_id AND r.resource_id = rt.resource_id
       LEFT JOIN tags t ON rt.tag_id = t.id
       WHERE r.bucket_id = $varchar
       GROUP BY (${ResourceRow.columns})
      """.query(json *: _varchar.opt).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

    val getById: Query[(String, String), ResourceRow] =
      sql"select to_json(r.*) from resources r where bucket_id = $varchar and resource_id = $varchar"
        .query(json)
        .map(_.as[ResourceRow].left.map(err => logger.warn(err)).toOption.get)

    val insert: Command[Json] =
      sql"insert into resources SELECT * FROM json_populate_record(NULL::resources, $json)".command

    val upsert: Command[Json] =
      sql"""
        INSERT INTO resources SELECT * FROM json_populate_record(NULL::resources, $json)
        ON CONFLICT (bucket_id, resource_id) DO UPDATE
        SET(user_id, hash, size, content_type, content_meta_tool_name, content_meta_tool_data, fs_path, fs_creation_time, fs_last_modified_time, title, description, thumbnail_timestamp) =
        (EXCLUDED.user_id, EXCLUDED.hash, EXCLUDED.size, EXCLUDED.content_type, EXCLUDED.content_meta_tool_name, EXCLUDED.content_meta_tool_data, EXCLUDED.fs_path, EXCLUDED.fs_creation_time, EXCLUDED.fs_last_modified_time, EXCLUDED.title, EXCLUDED.description, EXCLUDED.thumbnail_timestamp)
      """.command

    val bucketCount: Query[String, Int] =
      sql"select count(*) from resources where bucket_id = $varchar".query(int4)
  }
}
