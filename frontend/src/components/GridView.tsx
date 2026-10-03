import React, {CSSProperties, useEffect, useMemo, useRef, useState} from 'react';
import {ResourceSelection} from '../api/Model';
import './GridView.scss';
import TagBar from './navigation/TagBar';
import Preview, {PreviewOptions} from './Preview';
import InfiniteScroll from './common/InfiniteScroll';
import {findResources, FindResourcesParams, ResourceDto, SearchResponseDto} from "../api/generated";
import {gridColumnsForWidth, resourceSelectionToParams} from "../api/Util";
import {useResizeObserver} from "../api/ReactUtils";
import {useEventListener} from "./common/EventBus";

export type GalleryProps = {
  selection: ResourceSelection
  className?: string,
  style?: CSSProperties,
  componentType: 'page' | 'element'
  /** Target width (px) of a single cell; the column count is derived from the available width. */
  cellWidth: number,
  showTagbar: boolean,
  previewOptionsFn: (v: ResourceDto) => PreviewOptions,
  onClick: (v: ResourceDto) => void
}

const initialSearchResult: SearchResponseDto = { offset: 0, total: 0, results: [], tags: [] }

type GridCellProps = {
  resource: ResourceDto,
  columns: number,
  cellWidthPx: number,
  previewOptions: PreviewOptions,
  onClick: (v: ResourceDto) => void,
}

const GridCell = React.memo(({ resource, columns, cellWidthPx, previewOptions, onClick }: GridCellProps) => {
  const style = { "--ncols": `${columns}` } as CSSProperties

  return (
    <div className="grid-cell" style={style}>
      <Preview
        resource={resource}
        mediaWidthPx={cellWidthPx}
        onClick={onClick}
        options={previewOptions}
      />
    </div>
  )
})

const GridView = (props: GalleryProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching]     = useState(false)
  const [isEndReached, setIsEndReached] = useState(false)
  const { ref, width }                  = useResizeObserver<HTMLDivElement>();
  const [columns, setColumns]           = useState<number>(gridColumnsForWidth(props.cellWidth))

  // Tracks whether the next fetch restarts the list (selection changed) or
  // appends the next page, and lets responses from superseded requests be ignored.
  const resetRef     = useRef(false)
  const requestIdRef = useRef(0)

  function handleUpdate(resource: ResourceDto) {
    console.log(`Updating resource ${resource.resourceId} in grid view`)
    setSearchResult(prev => ({
      ...prev,
      results: prev.results.map(r => r.resourceId === resource.resourceId ? resource : r)
    }))
  }

  function handleDelete(deleted: ResourceDto) {
    setSearchResult(prev => ({
      ...prev,
      total: prev.total - 1,
      results: prev.results.filter(r => r.resourceId !== deleted.resourceId)
    }))
  }

  useEventListener('resource-updated', handleUpdate)
  useEventListener('resource-deleted', handleDelete)

  const gridSpacing = 1

  const fetchData = () => {

    const reset    = resetRef.current
    resetRef.current = false
    const previous = reset ? [] : searchResult.results
    const offset   = previous.length
    const n        = columns * 8

    if (n > 0 && !isEndReached) {

      const requestId = ++requestIdRef.current
      const params: FindResourcesParams = resourceSelectionToParams(props.selection, offset, n)

      findResources(params).then(response => {

          // A newer request (e.g. the selection changed again) superseded this one.
          if (requestId !== requestIdRef.current)
            return

          const videos = [...previous, ...response.results]

          if (videos.length >= response.total)
            setIsEndReached(true)

          setIsFetching(false);
          setSearchResult( {...response, results: videos } );
        });
      }
  }

  useEffect(() => {
    const componentWidth = props.componentType === 'page' ? window.innerWidth : width
    if (componentWidth === undefined)
      return

    const c = gridColumnsForWidth(props.cellWidth, componentWidth)
    if (c !== columns) {
      if (c > columns)
        setIsFetching(true)
      setColumns(c)
    }
  }, [width, props.cellWidth, props.componentType])

  // A new selection keeps the current results on screen and fetches its first
  // page in the background, replacing them once it arrives. Clearing the results
  // here would briefly unmount the whole grid and blank the tag bar, which is fed
  // by the same response.
  useEffect(() => {
    resetRef.current = true
    setIsFetching(true)
    setIsEndReached(false)
  }, [props.selection])

  useEffect(() => {
    if (isFetching && !isEndReached)
      fetchData()
  }, [isFetching, isEndReached, props.selection]);

  // The configured cell width is a stable, accurate basis for choosing a thumbnail size.
  const cellWidthPx = props.cellWidth

  // Memoize previews to prevent unnecessary re-creation of the array
  const previews = useMemo(() =>
    searchResult.results.map((vid) => (
      <GridCell
        key={`preview-${vid.resourceId}`}
        resource={vid}
        columns={columns}
        cellWidthPx={cellWidthPx}
        previewOptions={props.previewOptionsFn(vid)}
        onClick={props.onClick}
      />
    )), [searchResult.results, columns, cellWidthPx, props.previewOptionsFn, props.onClick]
  )

  let style = { "--grid-spacing" : `${gridSpacing}px` } as CSSProperties

  if (props.showTagbar)
    style = {...style, marginTop: 46 }

  return(
    <div className = { props.className } style = { props.style }>
      { props.showTagbar && <TagBar tags = { searchResult.tags } total = { searchResult.total } /> }
      <InfiniteScroll
        style        = { style }
        className    = "grid-container"
        fetchContent = { () => { if (!isFetching && !isEndReached) setIsFetching(true) } }
        scrollType   = { props.componentType }
        ref          = { ref }
        >
        { previews }
      </InfiniteScroll>
    </div>
  );
}

export default GridView;