import {CSSProperties, useEffect, useRef, useState} from "react"
import {FaSort} from "react-icons/fa"
import {FiEdit} from "react-icons/fi"
import ProgressiveImage from "react-progressive-graceful-image"
import {Api} from "../api/Api"
import {Resource, ResourceSelection, ResourceUserMeta, SearchResult} from "../api/Model"
import {dateMillisToString, formatByteSize} from "../api/Util"
import './ListView.scss'
import Scrollable from "./common/Scrollable"
import {useSortParam} from "../api/Constants"
import {useHistory} from "react-router-dom"
import TagsBar from "./common/TagsBar"
import {MdDelete, MdMovieEdit} from "react-icons/md";

type ListProps = {
  selection: ResourceSelection
  onClick: (v: Resource) => any
}

const initialSearchResult: SearchResult = { total: 0, results: [], tags: [] }
const rowHeight = 36

const ListView = (props: ListProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult);
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const history = useHistory();

  const fetchData = (previous: Array<Resource>) => {

    const offset = previous.length
    const n      = offset === 0 ? Math.ceil(window.outerHeight / rowHeight) : 32;

    if (n > 0 && fetchMore) {
      Api.searchMedia(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.results]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult({...response, results: videos});
        });
      }
  }

  const [sort, setSort] = useSortParam()

  useEffect(() => {
    setSearchResult(initialSearchResult)
    setIsFetching(true)
    setFetchMore(true)
  }, [props.selection])

  useEffect(() => { if (isFetching && fetchMore) fetchData(searchResult.results); }, [isFetching]);

  return (
      <Scrollable
        className = "list-container"
        fetchContent = { () => { if (!isFetching && fetchMore) setIsFetching(true); fetchData(searchResult.results) } }
        scrollType = 'page'
      >
      <div key="row-header" className="list-row">
        {/* <div className="list-cell list-header list-select"><input type="checkbox" /></div> */}
        <div className="list-cell list-header-thumbnail"></div>
        <div className="list-cell list-header">Title
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "title", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        <div className="list-cell list-header">Tags</div>
        <div className="list-cell list-header-date">Date
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "date_added", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        <div className="list-cell list-header-size">Size
          <FaSort className="column-sort-icon" onClick = { () => setSort({field: "size", direction: sort.direction === "asc" ? "desc" : "asc" }) } />
        </div>
        <div className="list-cell list-header-resolution">Quality
          {/* <FaSort className="column-sort-icon" onClick = { () => setSort({field: "resolution", direction: sort.direction === "asc" ? "desc" : "asc" }) } /> */}
          {/* <BsThreeDotsVertical className="list-menu-icon" /> */}
        </div>
      </div>
      
      <div key="row-spacer" className="list-row row-spacer"></div>
      {
        searchResult.results.map((v, index) => {
          return(
            <div key={`row-${v.resourceId}`} className="list-row">

              <div key="thumbnail" className="list-cell list-thumbnail">
                <ProgressiveImage src={v.urls.thumbnailUrl} placeholder="/image_placeholder.svg">
                    { (src: string) => 
                      <img className="list-thumbnail-img" src={src} onClick={() => props.onClick(v) } alt="an image" /> }
                </ProgressiveImage>
              </div>

              <TitleCell resource= { v } />
              
              <TagsCell resource= { v } />

              <div key="date" className="list-cell list-date">
                { dateMillisToString(v.uploadTimestamp) }
              </div>

              <div key="size" className="list-cell list-size">
                  { formatByteSize(v.resourceInfo.sizeInBytes, 1) }
              </div>

              <div key="resolution" className="list-cell list-resolution">
                <div className = "cell-wrapper">
                { 
                  Api.session().isAdmin() && 
                    <div className = "media-actions">
                      <MdMovieEdit className = "fragments-action" onClick = { () => history.push(`/editor/${v.resourceId}`) } />
                      <MdDelete className = "delete-action" />
                    </div> 
                }
                { `${v.resourceMeta.height}p` }
                </div>
                
              </div>
            </div>  
          );
        })
      }
      </Scrollable>
  );
}

const TagsCell = (props: {resource: Resource }) => {
  const [tags, setTags] = useState(props.resource.userMeta.tags)
  const isAdmin = Api.session().isAdmin()

  const updateTags = (newTags: Array<string>) => {
    const meta: ResourceUserMeta = { ...props.resource.userMeta, tags: newTags }
    Api.updateUserMetaData(props.resource.bucketId, props.resource.resourceId, meta).then(() =>  {
      setTags(newTags)
    })
  }
  return <TagsBar 
            tags = { tags }
            onTagsUpdated = { updateTags }
            showAddTagButton = {isAdmin} 
            showDeleteButton = {isAdmin} />
}

const TitleCell = (props: { resource: Resource} ) => {

  const [title, setTitle] = useState(props.resource.userMeta.title)
  const [editTitle, setEditTitle] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (editTitle)
      inputRef?.current?.focus()
  }, [editTitle])

  const updateTitle = (newTitle: string) => {
    const meta: ResourceUserMeta = { ...props.resource.userMeta, title: newTitle }
    Api.updateUserMetaData(props.resource.bucketId, props.resource.resourceId, meta).then(() =>  {
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

export default ListView