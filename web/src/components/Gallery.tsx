import React, { useEffect, useState } from 'react';
import {doGET} from '../api/Api';
import {SearchResult, Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {buildUrl, copyParams, useWindowSize, withFallback} from "../api/Util";
import GalleryPagination from "./GalleryPagination";
import TopNavBar from "./TopNavBar";

const pageSize = 12
const gridSize = 350
const gridReRenderThreshold = 24

const Gallery = () => {

  const location = useLocation();
  const [result, setResult] = useState(new SearchResult(0, 0, 0,[]))
  const urlParams = new URLSearchParams(location.search)
  const size = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  const current = parseInt(withFallback(urlParams.get("p"), "1"));

  useEffect(() => {

      const target = buildUrl("/api/videos", copyParams(urlParams).set("s", pageSize.toString()))
      console.log("render:" + target)

      doGET(target).then(response => { setResult(response); });
    }, [location]
  )

  const ncols = Math.min(Math.max(2, Math.round(size.width / gridSize)), 5);
  const nrows: number = Math.ceil(result.videos.length / ncols);
  const grid_class = `grid-${ncols}`

  let rows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= result.videos.length - 1) {
        const movie: Video = result.videos[idx];
        const link: string = "/video/" + movie.id;
        const duration: number = movie.duration

        return <td className={grid_class}><Thumbnail src={movie.thumbnail} link={link} title={movie.title} duration={duration}/></td>;
      } else {
        return <td className={grid_class}></td>;
      }

    });
    return <tbody><tr className="full-width"> {cols} </tr></tbody>;
  });

  return (
    <div className="full-width">
      <TopNavBar current={current} last={Math.trunc(result.total / pageSize)}  />
      <table className="gallery">
        {rows}
      </table>
      {/*<GalleryPagination current={current} last={Math.trunc(result.total / pageSize)} />*/}
    </div>
  );
}

export default Gallery;
