import React, { useEffect, useState } from 'react';
import {doGET} from '../api/Api';
import {SearchResult, Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import './Gallery.scss';
import {useLocation} from 'react-router-dom'
import {buildUrl, copyParams, useWindowSize, withFallback, zipArrays} from "../api/Util";
import TopNavBar from "./TopNavBar";
import {pageSizes} from "../api/Constants";

const gridSize = 320
const gridReRenderThreshold = 24

const Gallery = () => {

  const location = useLocation();
  const [result, setResult] = useState(new SearchResult(0, 0, 0,[]))
  const urlParams = new URLSearchParams(location.search)
  const size = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > gridReRenderThreshold));

  // grid size
  const ncols = Math.min(Math.max(2, Math.round(size.width / gridSize)), 5);
  const nrows: number = Math.ceil(result.videos.length / ncols);
  const grid_class = `grid-${ncols}`

  const pageSize = pageSizes.get(ncols) || 24

  const currentPage = parseInt(withFallback(urlParams.get("p"), "1"));

  useEffect(() => {

      const page = parseInt(urlParams.get("p") || "1")
      const offset = (page-1) * pageSize
      const apiParams = copyParams(urlParams).set("n", pageSize.toString()).set("offset", offset.toString())
      const target = buildUrl("/api/videos", apiParams)

      doGET(target).then(response => { setResult(response); });
    }, [location]
  )

  let rows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= result.videos.length - 1) {
        const vid: Video = result.videos[idx];
        const link: string = "/video/" + vid.id;
        const duration: number = vid.duration

        return <td className={grid_class}><Thumbnail vid={vid} /></td>;
      } else {
        return <td className={grid_class}></td>;
      }

    });

    return <tbody><tr className="full-width"> {cols} </tr></tbody>;
  });

  const titleRows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= result.videos.length - 1) {
        const vid: Video = result.videos[idx];
        return <td className="preview-footer"><div>{vid.title.substring(0, 30)}</div></td>;
      } else {
        return <td className="preview-footer"><div></div></td>;
      }
    });

    return <tbody><tr className="full-width"> {cols} </tr></tbody>;
  });

  const bothRows = zipArrays(rows, titleRows)

  return (
    <div className="full-width">
      <TopNavBar currentPage={currentPage} lastPage={Math.ceil(result.total / pageSize)} />
      <table className="gallery">
        {bothRows}
      </table>
    </div>
  );
}

export default Gallery;
