import React, { CSSProperties, useEffect, useState } from 'react';
import useResizeObserver from 'use-resize-observer';
import { Api } from '../api/Api';
import { Constants } from "../api/Constants";
import { Columns, MediaSelection, SearchResult, Media } from '../api/Model';
import './GridView.scss';
import TagBar from './navigation/TagBar';
import Preview, { PreviewOptions } from './Preview';
import Scrollable from './common/Scrollable';

export type GalleryProps = {
  selection: MediaSelection
  className?: string,
  style?: CSSProperties,
  componentType: 'page' | 'element'
  columns: Columns,
  showTagbar: boolean,
  previewOptionsFn: (v: Media) => PreviewOptions,
  onClick: (v: Media) => void
}

const initialSearchResult: SearchResult = { total: 0, media: [], tags: [] }

const GridView = (props: GalleryProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const { ref, width } = useResizeObserver<HTMLDivElement>();
  const [columns, setColumns] = useState<number>(props.columns === 'auto' ? 0 : props.columns)

  const gridSpacing = 1

  const fetchData = (previous: Array<Media>) => {

    const offset = previous.length
    const n      = columns * 8

    if (n > 0 && fetchMore) {
      Api.searchMedia(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.media]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult( {...response, media: videos } );
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
    setFetchMore(true)
  }, [props.selection])

  useEffect(() => { fetchData(searchResult.media) }, [columns])

  useEffect(() => { if (isFetching && fetchMore) fetchData(searchResult.media); }, [isFetching]);

  const previews = searchResult.media.map((vid, index) => {

    const style = { "--ncols" : `${columns}` } as CSSProperties

    return <div key = { `preview-${vid.id}` } className = "grid-cell" style = { style } >
              <Preview
                media    = { vid }
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
        fetchContent = { () => { if (!isFetching && fetchMore) setIsFetching(true); fetchData(searchResult.media) } }
        scrollType   = { props.componentType }
        ref          = { ref }
        >
        { previews }
      </Scrollable>
    </div>
  );
}

export default GridView;