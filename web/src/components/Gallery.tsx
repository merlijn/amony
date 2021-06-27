import React, { ChangeEvent, useEffect, useState } from 'react';
import {doGET} from '../api/Api';
import {SearchResult, Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import Pagination from 'react-bootstrap/Pagination';
import './Gallery.scss';
import {useHistory, useLocation} from 'react-router-dom'
import {buildUrl} from "../api/Util";

class State {
  constructor(
    public videos: Video[]
  ) { }
}

const Gallery = () => {

  const location = useLocation();
  const [result, setResult] = useState(new SearchResult(0, 0, 0,[]))

  useEffect(() => {

      const urlParams = new URLSearchParams(location.search)
      const newQ = urlParams.get("q")
      const newP = urlParams.get("p") ? urlParams.get("p") : 1;

      let newParams: any = { }
      if (newQ)
        newParams['q'] = newQ

      if (newP)
        newParams['p'] = newP

      const target = buildUrl("/api/videos", newParams)

      console.log("render:" + target)

      doGET(target).then(response => { setResult(response); });
    }, [location]
  )

  const ncols: number = 3;
  const nrows: number = Math.ceil(result.videos.length / ncols);

  let rows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= result.videos.length - 1) {
        const movie: Video = result.videos[idx];
        const link: string = "/video/" + movie.id;
        const duration: number = movie.duration

        return (
          <td className="gallery-column">
            <Thumbnail src={movie.thumbnail} link={link} title={movie.title} duration={duration}/>
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

  const location = useLocation();
  const history = useHistory();

  const navFirst = (e: any) => {
    e.preventDefault();
  };

  const navPrev = (e: any) => {
    e.preventDefault();
  };

  const navNext = (e: any) => {
    const urlParams = new URLSearchParams(location.search)
    const target = buildUrl("/search", { p: "2"})
    history.push(target);
  };

  const navLast = (e: any) => {
    e.preventDefault();
  };

  return (
    <Pagination>
      <Pagination.First onClick={navFirst}/>
      <Pagination.Prev onClick={navPrev} />
      <Pagination.Item active>{1}</Pagination.Item>
      <Pagination.Item>{2}</Pagination.Item>
      <Pagination.Ellipsis/>
      <Pagination.Item>{10}</Pagination.Item>
      <Pagination.Next onClick={navNext}/>
      <Pagination.Last onClick={navLast}/>
    </Pagination>
  );
}

export default Gallery;
