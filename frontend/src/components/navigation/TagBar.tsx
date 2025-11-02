import {useEffect, useState} from "react";
import {useLocation, useNavigate} from "react-router-dom";
import {buildUrl, copyParams} from "../../api/Util";
import './TagBar.scss';

const TagBar = (props: { tags: Array<string>, total: number }) => {

  const location = useLocation();
  const navigate = useNavigate();

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

    navigate(buildUrl("/search", newParams));
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
      <div key="total" className="tag-bar-total"> results: { props.total }</div>
    </div>);
}

export default TagBar