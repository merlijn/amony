import {CSSProperties, useContext, useEffect, useRef, useState} from "react"
import {FaSort} from "react-icons/fa"
import {FiEdit} from "react-icons/fi"
import ProgressiveImage from "react-progressive-graceful-image"
import {Api} from "../api/Api"
import {Resource, ResourceSelection, ResourceUserMeta, SearchResult} from "../api/Model"
import {dateMillisToString, formatByteSize} from "../api/Util"
import './ListView.scss'
import Scrollable from "./common/Scrollable"
import {SessionContext, useSortParam} from "../api/Constants"
import {useNavigate} from "react-router-dom"
import TagsBar from "./common/TagsBar"
import {MdDelete, MdMovieEdit} from "react-icons/md";
import {FaHashtag} from "react-icons/fa6";

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
  const [selectedItems, setSelectedItems] = useState<Array<number>>([])
  const navigate = useNavigate();
  const session = useContext(SessionContext)

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

  const toggle = (index: number)=>  {
    if (session.isAdmin()) {
      const i = selectedItems.indexOf(index)
      if (i > -1) {
        setSelectedItems(selectedItems.filter((v) => v !== index))
      } else {
        setSelectedItems([...selectedItems, index])
      }
    }
  }

  const headers =
    <tr key="row-header" className="list-row">
      { session.isAdmin() && <th className="list-header-select"><input type="checkbox"/></th> }
      <th className="list-header-thumbnail"></th>
      <th className="list-header-title"><span>Title</span>
        <FaSort className="column-sort-icon"
                onClick={() => setSort({field: "title", direction: sort.direction === "asc" ? "desc" : "asc"})}/>
      </th>
      <th className="list-header-tags"><span>Tags</span></th>
      <th className="list-header-date"><span>Date</span>
        <FaSort className="column-sort-icon" onClick={() => setSort({
          field: "date_added",
          direction: sort.direction === "asc" ? "desc" : "asc"
        })}/>
      </th>
      <th className="list-header-size"><span>Size</span>
        <FaSort className="column-sort-icon"
                onClick={() => setSort({field: "size", direction: sort.direction === "asc" ? "desc" : "asc"})}/>
      </th>
      <th className="list-header-resolution"><span>Quality</span>
        {/* <FaSort className="column-sort-icon" onClick = { () => setSort({field: "resolution", direction: sort.direction === "asc" ? "desc" : "asc" }) } /> */}
        {/* <BsThreeDotsVertical className="list-menu-icon" /> */}
      </th>
    </tr>

  const actionBar =
    <tr key="row-header" className="list-row">
      <th className = "list-header-select"><input type="checkbox"/></th>
      <th className = "list-header-actionbar" colSpan={6}>
        <FaHashtag className= "action-bar-item" />
        <MdDelete className = "action-bar-item" />
      </th>
    </tr>

  return (
    <Scrollable
      className="list-container"
      fetchContent={() => {
        if (!isFetching && fetchMore) setIsFetching(true);
        fetchData(searchResult.results)
      }}
      scrollType='page'
    >
      <tr key="row-column-width-spacer" style ={ {height: 0 } }>
        { session.isAdmin() && <td style = { {width: 36 } }></td> }
        <td style={{width: 72}}></td>
        <td style={{width: "65%"}}></td>
        <td style={{width: "35%"}}></td>
        <td style={{width: 110}}></td>
        <td style={{width: 100}}></td>
        <td style={{width: 80}}></td>
      </tr>

      {selectedItems.length > 0 ? actionBar : headers}

      <tr key="row-spacer" style = { { height : 4 } } />
        {
          searchResult.results.map((resource, index) => {
            return (
              <tr key={`row-${resource.resourceId}`} className="list-row">
                {
                  session.isAdmin() &&
                    <td key="select" className="list-select" onClick={() => { toggle(index) }}>
                      <input type="checkbox" checked={selectedItems.indexOf(index) > -1}/>
                    </td>
                }
                <td key="thumbnail" className="list-thumbnail" style = { { paddingLeft: session.isAdmin() ? 0 : 4}}>
                  <ProgressiveImage src={resource.urls.thumbnailUrl} placeholder="/image_placeholder.svg">
                    {(src: string) =>
                      <img className="list-thumbnail-img" src={src} onClick={() => props.onClick(resource)}
                           alt="an image"/>}
                  </ProgressiveImage>
                </td>

                <TitleCell mediaResource={resource} onClick={() => { toggle(index) }}/>

                <TagsCell resource={resource}/>

                <td key="date" className="list-cell list-date">
                  {dateMillisToString(resource.uploadTimestamp)}
                </td>

                <td key="size" className="list-cell list-size">
                  {formatByteSize(resource.resourceInfo.sizeInBytes, 1)}
                </td>

                <td key="resolution" className="list-cell list-resolution">
                  <div className="cell-wrapper">
                    {
                      session.isAdmin() &&
                        <div className="media-actions">
                            <MdMovieEdit className="fragments-action"
                                         onClick={() => navigate(`/editor/${resource.resourceId}`)}/>
                            <MdDelete className="delete-action"/>
                        </div>
                    }
                    {`${resource.resourceMeta.height}p`}
                  </div>

                </td>
              </tr>
            );
          })
        }
    </Scrollable>
);
}

const TagsCell = (props: {
  resource: Resource }) => {
  const [tags, setTags] = useState(props.resource.userMeta.tags)
  const session = useContext(SessionContext)

  const updateTags = (newTags: Array<string>) => {
    const meta: ResourceUserMeta = { ...props.resource.userMeta, tags: newTags }
    Api.updateUserMetaData(props.resource.bucketId, props.resource.resourceId, meta).then(() =>  {
      setTags(newTags)
    })
  }
  return <TagsBar 
            tags = { tags }
            onTagsUpdated = { updateTags }
            showAddTagButton = {session.isAdmin()} 
            showDeleteButton = {session.isAdmin()} />
}

type TitleCellProps =  { mediaResource: Resource; } & React.HTMLProps<HTMLTableCellElement>;

const TitleCell = ({ mediaResource, ...elementProps }: TitleCellProps ) => {

  const [title, setTitle] = useState(mediaResource.userMeta.title)
  const [editTitle, setEditTitle] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const session = useContext(SessionContext)

  useEffect(() => {
    if (editTitle)
      inputRef?.current?.focus()
  }, [editTitle])

  const updateTitle = (newTitle: string) => {
    const meta: ResourceUserMeta = { ...mediaResource.userMeta, title: newTitle }
    Api.updateUserMetaData(mediaResource.bucketId, mediaResource.resourceId, meta).then(() =>  {
      setTitle(newTitle)
      setEditTitle(false)
    })
  }

  const style: CSSProperties = editTitle ? { paddingLeft: "3px"} : {}

  return(
    <td style = { style } key="title" className="list-cell list-title" {...elementProps }>
      <div className="cell-wrapper">
        { !editTitle && title }
        { (!editTitle && session.isAdmin()) &&
            <FiEdit onClick = { () => { setEditTitle(true); } } className="edit-title" /> }
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
    </td>
  );
}

export default ListView