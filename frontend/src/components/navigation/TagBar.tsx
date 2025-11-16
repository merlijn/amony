import {useEffect, useMemo, useState} from "react";
import {useLocation, useNavigate} from "react-router-dom";
import {buildUrl, copyParams} from "../../api/Util";
import './TagBar.scss';

const NONE_TAG_LABEL = "<>"

const TagBar = (props: { tags: Array<string>, total: number }) => {

  const location = useLocation();
  const navigate = useNavigate();

  const [selectedTag, setSelectedTag] = useState<string | undefined>(undefined)
  const [untaggedSelected, setUntaggedSelected] = useState(false)

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const untaggedParam = (params.get("untagged") || "").toLowerCase() === "true"
    setUntaggedSelected(untaggedParam)

    if (untaggedParam)
      setSelectedTag(undefined)
    else
      setSelectedTag(params.get("tag") || undefined)
  }, [location]);

  const toggleTag = (tag: string) => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)
    newParams.delete("untagged")

    if (tag === selectedTag)
      newParams.delete("tag")
    else
      newParams.set("tag", tag)

    navigate(buildUrl("/search", newParams));
  };

  const toggleUntagged = () => {
    const params = new URLSearchParams(location.search)
    const newParams = copyParams(params)

    if (untaggedSelected)
      newParams.delete("untagged")
    else {
      newParams.set("untagged", "true")
      newParams.delete("tag")
    }

    navigate(buildUrl("/search", newParams));
  }

  const tags = useMemo(
    () => [NONE_TAG_LABEL, ...props.tags.filter(tag => tag !== NONE_TAG_LABEL)],
    [props.tags]
  )

  return (
    <div className="tag-bar">
      <div key="tags" className="tags">
        {
          tags.map(tag => {
            const isSelected = tag === NONE_TAG_LABEL ? untaggedSelected : tag === selectedTag
            const className = isSelected ? "tag selected-tag" : "tag"
            const onClick = tag === NONE_TAG_LABEL ? toggleUntagged : () => toggleTag(tag)

            return (
              <div key={`tag-${tag}`} className={className} onClick={onClick}>
                {tag}
              </div>
            )
          })
        }
      </div>
      <div key="total" className="tag-bar-total"> results: { props.total }</div>
    </div>);
}

export default TagBar
