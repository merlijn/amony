/**
import {Form} from "react-bootstrap";
import {imgAlt} from "../../api/Constants";
import React, {useState} from "react";

type Tag = {
  value: string,
  id: string
}

const TagList = (props: { tags: Array<Tag>, onTagsChanged?: (tags: Array<Tag>) => any }) => {

  const [tags, updateTags] = useState(props.tags)

  return (
    <div key="tag-list" className="tag-list">
      {
        props.tags.map((tag, index) => {
          return (
            <div key={`tag-${tag.id}`} className="tag-entry">
              <Form.Control
                className="tag-input" size="sm" type="text" defaultValue={tag.value}
                onChange={(e) => updateTag(index, e.target.value)}
              />
              <img alt={imgAlt}
                   className="action-icon-medium tag-delete-button"
                   onClick={(e) => removeTag(index)}
                   src="/cancel_black_24dp.svg"/>
            </div>);
        })
      }
      </div>
    );
}

export default TagList


 */

export {}