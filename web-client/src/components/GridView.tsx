import React, { CSSProperties, useEffect, useState } from 'react';
import useResizeObserver from 'use-resize-observer';
import { Api } from '../api/Api';
import { Constants } from "../api/Constants";
import { Columns, ResourceSelection, SearchResult, Resource } from '../api/Model';
import './GridView.scss';
import TagBar from './navigation/TagBar';
import Preview, { PreviewOptions } from './Preview';
import Scrollable from './common/Scrollable';

export type GalleryProps = {
  selection: ResourceSelection
  className?: string,
  style?: CSSProperties,
  componentType: 'page' | 'element'
  columns: Columns,
  showTagbar: boolean,
  previewOptionsFn: (v: Resource) => PreviewOptions,
  onClick: (v: Resource) => void
}

const initialSearchResult: SearchResult = { total: 0, results: [], tags: [] }

const GridView = (props: GalleryProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)
  const [isEndReached, setIsEndReached] = useState(false)
  const { ref, width } = useResizeObserver<HTMLDivElement>();
  const [columns, setColumns] = useState<number>(props.columns === 'auto' ? 0 : props.columns)

  const gridSpacing = 1

  const fetchData = () => {

    const previous = searchResult.results
    const offset = previous.length
    const n      = columns * 8

    if (n > 0 && !isEndReached) {
      Api.searchMedia(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.results]

          if (videos.length >= result.total)
            setIsEndReached(true)

          setIsFetching(false);
          setSearchResult( {...response, results: videos } );
        });
      }
  }

  useEffect(() => {
    if (props.columns === 'auto') {
      const componentWidth = props.componentType === 'page' ? window.innerWidth : width
      if (componentWidth !== undefined) {
        const c = Math.max(1, Math.round(componentWidth / Constants.gridSize));
        if (c !== columns) {
          if (c > columns)
            setIsFetching(true)

          setColumns(c)
        }
      }
    } else {
      setColumns(props.columns)
      if (props.columns > columns)
        setIsFetching(true)
    }

  }, [width, props.columns])

  useEffect(() => {
    setSearchResult(initialSearchResult)
    setIsFetching(true)
    setIsEndReached(false)
  }, [props.selection])

  useEffect(() => { if (isFetching && !isEndReached) fetchData(); }, [isFetching, isEndReached]);

  const previews = searchResult.results.map((vid, index) => {

    const style = { "--ncols" : `${columns}` } as CSSProperties

    return <div key = { `preview-${vid.resourceId}` } className = "grid-cell" style = { style } >
              <Preview
                resource= { vid }
                onClick  = { props.onClick }
                options  = { props.previewOptionsFn(vid) }
              />
            </div>
  })

  let style = { "--grid-spacing" : `${gridSpacing}px` } as CSSProperties

  if (props.showTagbar)
    style = {...style, marginTop: 46 }

  return(
    <div className = { props.className } style = { props.style }>
      { props.showTagbar && <TagBar tags = { searchResult.tags } /> }
      <Scrollable
        style        = { style }
        className    = "gallery-container"
        fetchContent = { () => { if (!isFetching && !isEndReached) setIsFetching(true) } }
        scrollType   = { props.componentType }
        ref          = { ref }
        >
        { previews }
      </Scrollable>
    </div>
  );
}

export default GridView;