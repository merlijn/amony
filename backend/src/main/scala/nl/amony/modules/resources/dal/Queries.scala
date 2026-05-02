package nl.amony.modules.resources.dal

import io.circe.Json
import scribe.Logging
import skunk.*
import skunk.circe.codec.all.*
import skunk.codec.all.*
import skunk.data.Arr
import skunk.implicits.*

object Queries extends Logging {

  object tags {
    val all: Query[Void, TagRow] = sql"select id, label from tags".query(TagRow.codec)

    def truncateCascade: Command[Void] = sql"truncate table tags cascade".command

    def getByLabels(n: Int): Query[List[String], TagRow] = sql"select id,label from tags where label in (${varchar.list(n)})".query(TagRow.codec)

    def getByIds(n: Int): Query[List[Int], TagRow] = sql"select id,label from tags where id in (${int4.list(n)})".query(TagRow.codec)

    def insert(n: Int): Command[List[String]] = sql"insert into tags (label) values ${varchar.values.list(n)}".command

    def upsert(n: Int): Command[List[String]] = sql"""
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
      sql"insert into resource_tags (bucket_id, resource_id, tag_id) values ${ResourceTagsRow.codec.values
          .list(n)} on conflict (bucket_id, resource_id, tag_id) do nothing".command

    val getById: Query[(String, String), ResourceTagsRow] =
      sql"select bucket_id, resource_id, tag_id from resource_tags where bucket_id = ${varchar(64)} and resource_id = ${varchar(64)}"
        .query(ResourceTagsRow.codec)

    def delete: Command[(String, String)] = sql"delete from resource_tags where bucket_id = $varchar and resource_id = $varchar".command
  }

  object collection_tags {

    val truncateCascade: Command[Void] = sql"truncate table collection_tags cascade".command

    def upsert(n: Int): Command[List[CollectionTagsRow]] =
      sql"insert into collection_tags (collection_id, tag_id) values ${CollectionTagsRow.codec.values.list(n)} on conflict (collection_id, tag_id) do nothing".command

    def delete: Command[java.util.UUID] = sql"delete from collection_tags where collection_id = $uuid".command
  }

  object collection_resources {

    val truncateCascade: Command[Void] = sql"truncate table collection_resources cascade".command

    val insert: Command[CollectionResourcesRow] =
      sql"insert into collection_resources (collection_id, bucket_id, resource_id) values ($uuid, ${varchar(64)}, ${varchar(64)}) on conflict (collection_id, bucket_id, resource_id) do nothing".command
        .to[CollectionResourcesRow]

    val delete: Command[(java.util.UUID, String, String)] =
      sql"delete from collection_resources where collection_id = $uuid and bucket_id = ${varchar(64)} and resource_id = ${varchar(64)}".command
  }

  object collections {

    val truncateCascade: Command[Void] = sql"truncate table collections cascade".command

    private val joinTables =
      sql"""
         SELECT to_json(c.*), array_agg(t.label) FILTER (WHERE t.label IS NOT NULL) as tags
         FROM collections c
         LEFT JOIN collection_tags ct ON c.id = ct.collection_id
         LEFT JOIN tags t ON ct.tag_id = t.id
       """

    val allJoined: Query[Void, (CollectionRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        GROUP BY (${CollectionRow.columns})
      """.query(json *: _varchar.opt).map((collection, tagLabels) => (collection.as[CollectionRow].toOption.get, tagLabels))

    val getByIdJoined: Query[java.util.UUID, (CollectionRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        WHERE c.id = $uuid
        GROUP BY (${CollectionRow.columns})
      """.query(json *: _varchar.opt).map((collection, tagLabels) => (collection.as[CollectionRow].toOption.get, tagLabels))

    val getByParentIdJoined: Query[Option[java.util.UUID], (CollectionRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        WHERE c.parent_id IS NOT DISTINCT FROM ${uuid.opt}
        GROUP BY (${CollectionRow.columns})
      """.query(json *: _varchar.opt).map((collection, tagLabels) => (collection.as[CollectionRow].toOption.get, tagLabels))

    val getByResourceIdJoined: Query[(String, String), (CollectionRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        INNER JOIN collection_resources cr ON c.id = cr.collection_id
        WHERE cr.bucket_id = ${varchar(64)} AND cr.resource_id = ${varchar(64)}
        GROUP BY (${CollectionRow.columns})
      """.query(json *: _varchar.opt).map((collection, tagLabels) => (collection.as[CollectionRow].toOption.get, tagLabels))

    val insert: Command[Json] = sql"insert into collections SELECT * FROM json_populate_record(NULL::collections, $json)".command

    val upsert: Command[Json] = sql"""
        INSERT INTO collections SELECT * FROM json_populate_record(NULL::collections, $json)
        ON CONFLICT (id) DO UPDATE
        SET(parent_id, description) = (EXCLUDED.parent_id, EXCLUDED.description)
      """.command

    val delete: Command[java.util.UUID] = sql"delete from collections where id = $uuid".command
  }

  object resources {

    val truncateCascade: Command[Void] = sql"truncate table resources cascade".command

    val deleteBucket: Command[(String, String)] = sql"delete from resources where bucket_id = $varchar and resource_id = $varchar".command

    private val joinTables =
      sql"""
         SELECT to_json(r.*), array_agg(t.label) FILTER (WHERE t.label IS NOT NULL) as tags
         FROM resources r
         LEFT JOIN resource_tags rt
           ON r.bucket_id = rt.bucket_id AND r.resource_id = rt.resource_id
         LEFT JOIN tags t ON rt.tag_id = t.id
       """

    val getByPartialHashJoined: Query[(String, String), (ResourceRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        WHERE r.bucket_id = $varchar AND r.partial_hash = $varchar
        GROUP BY (${ResourceRow.columns})
      """.query(json *: _varchar.opt).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

    val getByIdJoined: Query[(String, String), (ResourceRow, Option[Arr[String]])] =
      sql"""
        $joinTables
        WHERE r.bucket_id = $varchar AND r.resource_id = $varchar
        GROUP BY (${ResourceRow.columns})
      """.query(json *: _varchar.opt).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

    val allJoined: Query[String, (ResourceRow, Option[Arr[String]])] =
      sql"""
       $joinTables
       WHERE r.bucket_id = $varchar
       GROUP BY (${ResourceRow.columns})
      """.query(json *: _varchar.opt).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

    val getByCollectionIdJoined: Query[java.util.UUID, (ResourceRow, Option[Arr[String]])] =
      sql"""
       SELECT to_json(r.*), array_agg(t.label) FILTER (WHERE t.label IS NOT NULL) as tags
       FROM resources r
       INNER JOIN collection_resources cr
         ON r.bucket_id = cr.bucket_id AND r.resource_id = cr.resource_id
       LEFT JOIN resource_tags rt
         ON r.bucket_id = rt.bucket_id AND r.resource_id = rt.resource_id
       LEFT JOIN tags t ON rt.tag_id = t.id
       WHERE cr.collection_id = $uuid
       GROUP BY (${ResourceRow.columns})
      """.query(json *: _varchar.opt).map((resource, tagLabels) => (resource.as[ResourceRow].toOption.get, tagLabels))

    val getById: Query[(String, String), ResourceRow] =
      sql"select to_json(r.*) from resources r where bucket_id = $varchar and resource_id = $varchar".query(json)
        .map(_.as[ResourceRow].left.map(err => logger.warn(err)).toOption.get)

    val insert: Command[Json] = sql"insert into resources SELECT * FROM json_populate_record(NULL::resources, $json)".command

    def modifyTagsBulk(resourceIdSize: Int, tagsToAddSize: Int, tagsToRemoveSize: Int) = sql"""
          WITH
          params AS (
            SELECT
              $varchar::VARCHAR(64) AS bucket_id,
              ${varchar.list(resourceIdSize)}::VARCHAR(64)[] AS resource_ids,
              ${int4.list(tagsToAddSize)}::INTEGER[] AS tags_to_add,
              ${int4.list(tagsToRemoveSize)}::INTEGER[] AS tags_to_remove
          ),
          deleted AS (
              DELETE FROM resource_tags
              WHERE bucket_id = (SELECT bucket_id FROM params)
                AND resource_id = ANY((SELECT resource_ids FROM params))
                AND tag_id = ANY((SELECT tags_to_remove FROM params))
              RETURNING bucket_id, resource_id, tag_id
          ),
          inserted AS (
              INSERT INTO resource_tags (bucket_id, resource_id, tag_id)
              SELECT
                  p.bucket_id,
                  unnest(p.resource_ids) AS resource_id,
                  unnest(p.tags_to_add) AS tag_id
              FROM params p
              ON CONFLICT (bucket_id, resource_id, tag_id) DO NOTHING
              RETURNING bucket_id, resource_id, tag_id
          )
          SELECT
              (SELECT COUNT(*) FROM deleted) AS tags_removed,
              (SELECT COUNT(*) FROM inserted) AS tags_added;
          """.command

    val upsert: Command[Json] = sql"""
        INSERT INTO resources SELECT * FROM json_populate_record(NULL::resources, $json)
        ON CONFLICT (bucket_id, resource_id) DO UPDATE
        SET(user_id, partial_hash, size, content_type, content_meta_tool_name, content_meta_tool_data, fs_path, time_added, time_created, time_last_modified, title, description, thumbnail_timestamp) =
        (EXCLUDED.user_id, EXCLUDED.partial_hash, EXCLUDED.size, EXCLUDED.content_type, EXCLUDED.content_meta_tool_name, EXCLUDED.content_meta_tool_data, EXCLUDED.fs_path, EXCLUDED.time_added, EXCLUDED.time_created, EXCLUDED.time_last_modified, EXCLUDED.title, EXCLUDED.description, EXCLUDED.thumbnail_timestamp)
      """.command

    val bucketCount: Query[String, Int] = sql"select count(*) from resources where bucket_id = $varchar".query(int4)
  }
}
