import { useEffect, useState } from "react";
import { useHistory, useLocation } from "react-router-dom";
import { Api } from "../../api/Api";
import { buildUrl, copyParams } from "../../api/Util";
import './TagBar.scss';

const TagBar = (props: { tags: Array<string> }) => {

  const location = useLocation();
  const history = useHistory();

  const [selectedTag, setSelectedTag] = useState<string | undefined>(undefined)
  // const [tags, setTags] = useState<Array<string>>(props.tags)

  useEffect(() => {
    setSelectedTag(new URLSearchParams(location.search).get("tag") || undefined)
  }, [location]);

  const toggleTag = (tag: string) => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)

    if (tag === selectedTag)
      newParams.delete("tag")
    else
      newParams.set("tag", tag)

    // setSelectedTag(tag)
    history.push(buildUrl("/search", newParams));
  };

  return (
    <div className="tag-bar">
      <div key="tags" className="tags">
        {
          props.tags.map(tag =>
            <div key={`tag-${tag}`} className={ tag === selectedTag ? "tag selected-tag" : "tag"} onClick = {() => toggleTag(tag) }>{tag}</div>
          )
        }
      </div>

    </div>);
}

export default TagBar