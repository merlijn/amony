import { CSSProperties, useEffect, useRef, useState } from "react"
import { FaSort } from "react-icons/fa"
import { FiEdit, FiPlusCircle } from "react-icons/fi"
import { IoCutSharp } from "react-icons/io5"
import { AiOutlineDelete } from "react-icons/ai"
import ProgressiveImage from "react-progressive-graceful-image"
import { Api } from "../api/Api"
import { MediaSelection, SearchResult, Video, VideoMeta } from "../api/Model"
import { dateMillisToString, formatByteSize } from "../api/Util"
import './ListView.scss'
import Scrollable from "./common/Scrollable"
import TagEditor from "./common/TagEditor"
import { useUrlParam } from "../api/ReactUtils"
import { useSortParam } from "../api/Constants"
import { useHistory } from "react-router-dom"

type ListProps = {
  selection: MediaSelection
  onClick: (v: Video) => any
}

const initialSearchResult: SearchResult = { total: 0, videos: [] }
const rowHeight = 36

const ListView = (props: ListProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult);
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const history = useHistory();

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = offset === 0 ? Math.ceil(window.outerHeight / rowHeight) : 32;

    if (n > 0 && fetchMore) {
      Api.getVideoSelection(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.videos]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult({...response, videos: videos});
        });
      }
  }

  const [sort, setSort] = useSortParam()

  useEffect(() => {
    console.log("fetch data")
    setSearchResult(initialSearchResult)
    setIsFetching(true)
    setFetchMore(true)
  }, [props.selection])

  useEffect(() => { if (isFetching && fetchMore) fetchData(searchResult.videos); }, [isFetching]);

  return (
      <Scrollable
        className = "list-container"
        fetchContent = { () => { if (!isFetching && fetchMore) setIsFetching(true); fetchData(searchResult.videos) } }
        scrollType = 'page'
      >
      <div key="row-header" className="list-row">
        {/* <div className="list-cell list-header list-select"><input type="checkbox" /></div> */}
        <div className="list-cell list-header"></div>
        <div className="list-cell list-header">Title
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "title", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        <div className="list-cell list-header">Tags</div>
        <div className="list-cell list-header">
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "date_added", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        <div className="list-cell list-header">
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "size", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        {/* <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div> */}
        <div className="list-cell list-header">
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "resolution", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
          {/* <BsThreeDotsVertical className="list-menu-icon" /> */}
        </div>
      </div>
      
      <div key="row-spacer" className="list-row row-spacer"></div>
      {
        searchResult.videos.map((v, index) => {
          return(
            <div key={`row-${v.id}`} className="list-row">

              <div key="thumbnail" className="list-cell list-thumbnail">
                <ProgressiveImage src={v.thumbnail_url} placeholder="/image_placeholder.svg">
                    { (src: string) => 
                      <img className="list-thumbnail-img" src={src} onClick={() => props.onClick(v) } alt="an image" /> }
                </ProgressiveImage>
              </div>

              <TitleCell video = { v } />
              
              <TagsCell video = { v } />

              <div key="date" className="list-cell list-date">
                { dateMillisToString(v.addedOn) }
              </div>

              <div key="size" className="list-cell list-size">
                  { formatByteSize(v.size, 1) }
              </div>

              <div key="resolution" className="list-cell list-resolution">
                <div className = "cell-wrapper">
                <div className = "media-actions">
                  <IoCutSharp className = "fragments-action" onClick = { () => history.push(`/editor/${v.id}`) } />
                  <AiOutlineDelete className = "delete-action" />
                </div>
                { `${v.height}p` }
                </div>
                
              </div>

              {/* <div key="actions" className="list-cell list-actions">
                <div className="actions-container">
                  <RiDeleteBin6Line className="delete-action" />
                  <FiDownload className="delete-action" />
                  </div>
              </div> */}
            </div>  
          );
        })
      }
      </Scrollable>
  );
}

const TitleCell = (props: { video: Video} ) => {

  const [title, setTitle] = useState(props.video.meta.title)
  const [editTitle, setEditTitle] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (editTitle)
      inputRef?.current?.focus()
  }, [editTitle])

  const updateTitle = (newTitle: string) => {
    const meta: VideoMeta = { ...props.video.meta, title: newTitle }
    Api.updateVideoMetaData(props.video.id, meta).then(() =>  {
      setTitle(newTitle)
      setEditTitle(false)
    })
  }

  const style: CSSProperties = editTitle ? { paddingLeft: "3px"} : {}

  return(
    <div style = { style } key="title" className="list-cell list-title">
      <div className="cell-wrapper">
        { !editTitle && title }
        { (!editTitle && Api.session().isAdmin()) && <FiEdit onClick = { () => { setEditTitle(true); } } className="edit-title action-icon hover-action" /> }
        { editTitle && 
          <input 
            ref        = { inputRef } 
            className  = "edit-title-input"
            type       = "text" 
            value      = { title } 
            onBlur     = { () => { setEditTitle(false) } } 
            onChange   = { (e) => { setTitle(e.target.value ) } }
            onKeyPress = { (e) => {
              if (e.key === "Enter") {
                e.preventDefault()
                updateTitle(title);
              }
            }}
          />
        }
      </div>
    </div>
  );
}


const TagsCell = (props: { video: Video }) => {

  const [tags, setTags] = useState(props.video.meta.tags)
  const [showNewTag, setShowNewTag] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const updateTags = (newTags: Array<string>) => {
    const meta: VideoMeta = { ...props.video.meta, tags: newTags }
    Api.updateVideoMetaData(props.video.id, meta).then(() =>  {
      setTags(newTags)
      setShowNewTag(false)
    })
  }

  useEffect(() => {
    if (showNewTag)
      inputRef?.current?.focus()
  }, [showNewTag]);

  return(
    <div key="tags" className="list-cell list-tags">
      <div className = "cell-wrapper">
        <TagEditor 
          key              = "tag-editor" 
          showAddButton    = { false }
          showDeleteButton = { Api.session().isAdmin() }
          tags             = { tags } 
          callBack         = { (newTags) => { updateTags(newTags) } } />
        { (!showNewTag && Api.session().isAdmin()) && <FiPlusCircle onClick = { (e) => setShowNewTag(true) } className="add-tag-action" /> }
        <span 
          contentEditable
          key        = "new-tag"
          className  = "new-tag"
          ref        = { inputRef } 
          style      = { { visibility: showNewTag ? "visible" : "hidden" } }
          onBlur     = { (e) => { 
            e.currentTarget.innerText = ""
            setShowNewTag(false) } 
          } 
          onKeyPress = { (e) => {
            if (e.key === "Enter") {
              e.preventDefault()
              const newTag = e.currentTarget.innerText
              e.currentTarget.innerText = ""
              updateTags([...tags, newTag.trim()])
            }
            if (e.key === "Escape") {
              e.currentTarget.blur();
            }
          }
        } ></span>
      </div>
    </div>);
}

export default ListView