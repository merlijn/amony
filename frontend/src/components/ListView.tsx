import React, {CSSProperties, useContext, useEffect, useMemo, useRef, useState} from "react"
import {FaSort} from "react-icons/fa"
import {FiEdit} from "react-icons/fi"
import {ResourceSelection} from "../api/Model"
import {dateMillisToString, formatByteSize, resourceSelectionToParams, titleFromPath} from "../api/Util"
import './ListView.scss'
import InfiniteScroll from "./common/InfiniteScroll"
import {SessionContext, useSortParam} from "../api/Constants"
import {useNavigate} from "react-router-dom"
import TagsBar from "./common/TagsBar"
import {MdDelete, MdMovieEdit} from "react-icons/md";
import {FaHashtag} from "react-icons/fa6";
import {
  findResources,
  FindResourcesParams,
  ResourceDto,
  SearchResponseDto,
  updateUserMetaData,
  UserMetaDto
} from "../api/generated";
import LazyImage from "./common/LazyImage";
import BulkUpdateTagsDialog from "./dialogs/BulkUpdateTagsDialog";

type ListProps = {
  selection: ResourceSelection
  onClick: (v: ResourceDto) => any
}

const initialSearchResult: SearchResponseDto = { offset: 0, total: 0, results: [], tags: [] }
const rowHeight = 36

const ListView = (props: ListProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult);
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const [selectedItems, setSelectedItems] = useState<Array<number>>([])

  const [showBulkTagModal, setShowBulkTagModal] = useState(false)

  const navigate = useNavigate();
  const session = useContext(SessionContext)

  const selectedResources = useMemo(
    () => selectedItems
      .map(index => searchResult.results[index])
      .filter((resource): resource is ResourceDto => resource !== undefined),
    [selectedItems, searchResult.results]
  )

  const fetchData = (previous: Array<ResourceDto>) => {

    const offset = previous.length
    const n      = offset === 0 ? Math.ceil(window.outerHeight / rowHeight) : 32;

    if (n > 0 && fetchMore) {

      const params: FindResourcesParams = resourceSelectionToParams(props.selection, offset, n)

      findResources(params).then(response => {

        const videos = [...previous, ...response.results]

        if (videos.length >= response.total)
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

  useEffect(() => {
    setSelectedItems(previous => previous.filter(index => index < searchResult.results.length))
  }, [searchResult.results.length])

  const toggle = (index: number)=>  {
    if (session.isAdmin()) {
      setSelectedItems(prev => {
        const i = prev.indexOf(index)
        if (i > -1) {
          return prev.filter((v) => v !== index)
        } else {
          return [...prev, index]
        }
      })
    }
  }

  const toggleAll = (checked: boolean) => {
    if (!session.isAdmin()) return

    if (checked) {
      setSelectedItems(searchResult.results.map((_, idx) => idx))
    } else {
      setSelectedItems([])
    }
  }

  const openBulkTagModal = () => { setShowBulkTagModal(true) }
  const closeBulkTagModal = () => { setShowBulkTagModal(false) }

  const handleBulkTagsUpdated = (result: { tagsAdded: string[]; tagsRemoved: string[] }) => {
    if (selectedResources.length === 0) {
      setShowBulkTagModal(false)
      return
    }

    const selectedIds = new Set(selectedResources.map(resource => resource.resourceId))

    setSearchResult(previous => ({
      ...previous,
      results: previous.results.map(resource => {
        if (!selectedIds.has(resource.resourceId)) {
          return resource
        }
        const tagSet = new Set(resource.tags)
        result.tagsRemoved.forEach(tag => tagSet.delete(tag))
        result.tagsAdded.forEach(tag => tagSet.add(tag))
        return {
          ...resource,
          tags: Array.from(tagSet).sort((a, b) => a.localeCompare(b))
        }
      })
    }))

    setSelectedItems([])
    setShowBulkTagModal(false)
  }

  const allSelected = selectedItems.length > 0 && selectedItems.length === searchResult.results.length && searchResult.results.length > 0

  const headers =
    <tr key="row-header" className="list-row">
      { session.isAdmin() && <th className="list-header-select"><input type="checkbox" checked={allSelected} onChange={(event) => toggleAll(event.target.checked)} /></th> }
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
      <th className="list-header-resolution last-column"><span>Quality</span>
        {/* <FaSort className="column-sort-icon" onClick = { () => setSort({field: "resolution", direction: sort.direction === "asc" ? "desc" : "asc" }) } /> */}
        {/* <BsThreeDotsVertical className="list-menu-icon" /> */}
      </th>
    </tr>

  const actionBar =
    <tr key="row-header" className="list-row">
      <th className = "list-header-select"><input type="checkbox" checked={allSelected} onChange={(event) => toggleAll(event.target.checked)} /></th>
      <th className = "list-header-actionbar" colSpan={6}>
        <FaHashtag className= "action-bar-item" title="Update tags" onClick = { openBulkTagModal } />
        <MdDelete className = "action-bar-item" />
      </th>
    </tr>

  return (
    <>
      <InfiniteScroll
        className="list-container"
        fetchContent={() => {
          if (!isFetching && fetchMore) setIsFetching(true);
            fetchData(searchResult.results)
        }}
        scrollType='page'
      >
      <table className="list-table">

      {/*<tr key="row-column-width-spacer" style ={ {height: 0 } }>*/}
      {/*  { session.isAdmin() && <td style = { {width: 36 } }></td> }*/}
      {/*  <td style={{width: 72}}></td>*/}
      {/*  <td style={{width: "65%"}}></td>*/}
      {/*  <td style={{width: "35%"}}></td>*/}
      {/*  <td style={{width: 110}}></td>*/}
      {/*  <td style={{width: 100}}></td>*/}
      {/*  <td style={{width: 80}}></td>*/}
      {/*</tr>*/}

      <thead>
        {selectedItems.length > 0 ? actionBar : headers}
      </thead>

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
                <td key="thumbnail" className="list-thumbnail" style = { { paddingLeft: session.isAdmin() ? 0 : 2}}>

                  <LazyImage
                    loadImage = { () =>
                      <img
                        src       = { resource.urls.thumbnailUrl } alt="an image"
                        onClick   = { () => props.onClick(resource) }
                        className = "list-thumbnail-img"
                      />
                    }
                  />
                </td>

                <TitleCell mediaResource={resource} onClick={() => { toggle(index) }}/>

                <TagsCell resource={resource}/>

                <td key="date" className="list-cell list-date">
                  {dateMillisToString(resource.timeCreated ?? resource.timeAdded)}
                </td>

                <td key="size" className="list-cell list-size">
                  {formatByteSize(resource.sizeInBytes, 1)}
                </td>

                <td key="resolution" className="list-cell list-resolution last-column">
                  <div className="cell-wrapper">
                    {
                      session.isAdmin() &&
                        <div className="media-actions">
                            <MdMovieEdit className="fragments-action"
                                         onClick={() => navigate(`/editor/${resource.bucketId}/${resource.resourceId}`)}/>
                            <MdDelete className="delete-action"/>
                        </div>
                    }
                    {`${resource.contentMeta.height}p`}
                  </div>

                </td>
              </tr>
            );
          })
        }
        </table>
      </InfiniteScroll>

      <BulkUpdateTagsDialog
        visible           = { showBulkTagModal }
        onHide            = { closeBulkTagModal }
        onUpdate          = { handleBulkTagsUpdated }
        selectedResources = { selectedResources }
      />
    </>
  );
}

const TagsCell = (props: {
  resource: ResourceDto }) => {
  const [tags, setTags] = useState(props.resource.tags)
  const session = useContext(SessionContext)

  useEffect(() => { setTags(props.resource.tags) }, [props.resource.tags])

  const updateTags = (newTags: Array<string>) => {
    const meta: UserMetaDto = { title: props.resource.title, description: props.resource.description, tags: newTags }

    updateUserMetaData(props.resource.bucketId, props.resource.resourceId, meta).then(() =>  {
      setTags(newTags)
    })
  }
  return <TagsBar 
            tags = { tags }
            onTagsUpdated = { updateTags }
            showAddTagButton = {session.isAdmin()}
            showDeleteButton = {session.isAdmin()} />
}

type TitleCellProps =  { mediaResource: ResourceDto; } & React.HTMLProps<HTMLTableCellElement>;

const TitleCell = ({ mediaResource, ...elementProps }: TitleCellProps ) => {

  const initialTitle = mediaResource.title || titleFromPath(mediaResource.path)
  const [title, setTitle] = useState(initialTitle)
  const [editTitle, setEditTitle] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const session = useContext(SessionContext)

  useEffect(() => {
    if (editTitle)
      inputRef?.current?.focus()
  }, [editTitle])

  const updateTitle = (newTitle: string) => {
    const meta: UserMetaDto = { tags: mediaResource.tags, description: mediaResource.description, title: newTitle }

    updateUserMetaData(mediaResource.bucketId, mediaResource.resourceId, meta).then(() =>  {
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
                title && updateTitle(title);
              }
            }}
          />
        }
      </div>
    </td>
  );
}

export default ListView
