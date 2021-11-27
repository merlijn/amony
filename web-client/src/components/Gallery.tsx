import React, { useEffect, useState } from 'react';
import useResizeObserver from 'use-resize-observer';
import { Api } from '../api/Api';
import { Constants } from "../api/Constants";
import { Columns, SearchResult, SortDirection, Video } from '../api/Model';
import { useListener } from '../api/ReactUtils';
import './Gallery.scss';
import Preview, { PreviewOptions } from './Preview';

const fetchDataScreenMargin = 1024;

export type MediaSelection = {
  query?: string
  directory?: string
  minimumQuality: number
  sortField: string,
  sortDirection: SortDirection,
}

export type GalleryProps = {
  selection: MediaSelection
  scroll: 'page' | 'element'
  columns: Columns,
  previewOptionsFn: (v: Video) => PreviewOptions,
  onClick: (v: Video) => void
}

const initialSearchResult: SearchResult = { total: 0, videos: [] }

const Gallery = (props: GalleryProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult)
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)
  const [columns, setColumns] = useState<number>(props.columns === 'auto' ? 0 : props.columns)
  const {ref, width} = useResizeObserver<HTMLDivElement>();

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = columns * 5

    if (n > 0 && fetchMore) {
      Api.getVideos(
        props.selection.query || "",
        n,
        offset,
        props.selection.directory,
        props.selection.minimumQuality,
        props.selection.sortField,
        props.selection.sortDirection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.videos]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult({...response, videos: videos});
        });
      }
  }

  const onPageScroll = (e: Event) => {

    const withinFetchMargin = 
        document.documentElement.offsetHeight - Math.ceil(window.innerHeight + document.documentElement.scrollTop) <=  fetchDataScreenMargin

    if (props.scroll === 'page' && withinFetchMargin && !isFetching && fetchMore)
      setIsFetching(true)
  }

  const onElementScroll = (e: React.UIEvent<HTMLDivElement, UIEvent>) => { 

    const withinFetchMargin = 
      (e.currentTarget.scrollTop + e.currentTarget.clientHeight) >= e.currentTarget.scrollHeight;

    if (props.scroll === 'element' && withinFetchMargin && !isFetching && fetchMore)
      setIsFetching(true)
  }

  useListener('scroll', onPageScroll)

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
              style = {style} className="grid-cell"
              key = {`preview-${vid.id}`}
              vid = {vid}
              onClick = { props.onClick }
              options = { props.previewOptionsFn(vid) }
            />
  })

  return (
    <div className="gallery-container" ref = {ref} onScroll = { onElementScroll }>
      { previews }
    </div>
  );
}

export default Gallery;