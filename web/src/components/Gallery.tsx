import React, { ChangeEvent, useEffect, useState } from 'react';
import {doGET} from '../api/Api';
import {Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import Pagination from 'react-bootstrap/Pagination';
import './Gallery.scss';
import { useLocation } from 'react-router-dom'

class State {
  constructor(
    public videos: Video[]
  ) { }
}

function Gallery() {

  const location = useLocation();
  const [videos, setVideos] = useState([])
  // const [q, setQ] = useState<string | null>(searchParams.get("q"))

  useEffect(() => {

      const newQ = new URLSearchParams(location.search).get("q")
      const path = (newQ) ? '/api/videos?q=' + newQ : '/api/videos'
      console.log("Gallery.update: " + path)

      doGET(path).then(videosFromServer => {
        console.log("Gallery.fetch: " + path)
        setVideos(videosFromServer);
      });
    }, [location]
  )

  const ncols: number = 3;
  const nrows: number = Math.ceil(videos.length / ncols);

  let rows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= videos.length - 1) {
        const movie: Video = videos[idx];
        const link: string = "/video/" + movie.id;
        return (
          <td className="gallery-column">
            <Thumbnail src={movie.thumbnail} link={link} title={movie.title}/>
          </td>);
      } else {
        return <td className="gallery-column"></td>;
      }

    });
    return <tr className="gallery-row full-width"> {cols} </tr>;
  });

  return (
    <div className="full-width">
      <table className="gallery">
        {rows}
      </table>
      <Footer current={1} last={10} />
    </div>
  );
}

function Footer(props: { current: number, last: number }) {
  return (
    <Pagination>
      <Pagination.First/>
      <Pagination.Prev/>
      <Pagination.Item active>{1}</Pagination.Item>
      <Pagination.Item>{2}</Pagination.Item>
      <Pagination.Ellipsis/>
      <Pagination.Item>{10}</Pagination.Item>
      <Pagination.Next/>
      <Pagination.Last/>
    </Pagination>
  );
}

export default Gallery;
