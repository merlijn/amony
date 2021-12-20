import React, { useEffect, useState } from 'react';
import useResizeObserver from 'use-resize-observer';
import { Api } from '../api/Api';
import { Constants } from "../api/Constants";
import { Columns, MediaSelection, SearchResult, Video } from '../api/Model';
import './GridView.scss';
import TagBar from './navigation/TagBar';
import Preview, { PreviewOptions } from './Preview';
import Scrollable from './shared/Scrollable';

export type GalleryProps = {
  selection: MediaSelection
  scroll: 'page' | 'element'
  columns: Columns,
  previewOptionsFn: (v: Video) => PreviewOptions,
  onClick: (v: Video) => void
}

const initialSearchResult: SearchResult = { total: 0, videos: [] }

const GridView = (props: GalleryProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const [columns, setColumns] = useState<number>(props.columns === 'auto' ? 0 : props.columns)
  const {ref, width} = useResizeObserver<HTMLDivElement>();
  const gridSpacing = 1

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = columns * 8

    if (n > 0 && fetchMore) {
      Api.getVideoSelection(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.videos]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult( {...response, videos: videos } );
        });
      }
  }

  useEffect(() => {
    if (props.columns === 'auto') {
      if (width !== undefined) {
        const c = Math.max(1, Math.round(width / Constants.gridSize));
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

  useEffect(() => { fetchData(searchResult.videos) }, [columns])

  useEffect(() => { if (isFetching && fetchMore) fetchData(searchResult.videos); }, [isFetching]);

  const previews = searchResult.videos.map((vid) => {

    const style: { } = { "--ncols" : `${columns}` }

    return <Preview
              style     = { style } 
              className = "grid-cell"
              key       = { `preview-${vid.id}` }
              vid       = { vid }
              onClick   = { props.onClick }
              options   = { props.previewOptionsFn(vid) }
            />
  })

  const containerStyle: { } = { "--grid-spacing" : `${gridSpacing}px` }

  return(
    <>
      <TagBar />
      <Scrollable
        style        = { containerStyle }
        className    = "gallery-container"
        fetchContent = { () => { if (!isFetching && fetchMore) setIsFetching(true); fetchData(searchResult.videos) } }
        scrollType   = { props.scroll }
        ref          = { ref }>
        { previews }
      </Scrollable>
    </>
  );
}

export default GridView;