import React, { ChangeEvent, useEffect, useState } from 'react';
import {doGET} from '../api/Api';
import {SearchResult, Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import Pagination from 'react-bootstrap/Pagination';
import './Gallery.scss';
import {useHistory, useLocation} from 'react-router-dom'
import {buildUrl, copyParams, useWindowSize, withFallback} from "../api/Util";

const Gallery = () => {

  const location = useLocation();
  const [result, setResult] = useState(new SearchResult(0, 0, 0,[]))
  const urlParams = new URLSearchParams(location.search)
  const size = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > 20));

  const pageSize = 6
  const current = parseInt(withFallback(urlParams.get("p"), "1"));

  useEffect(() => {

      const target = buildUrl("/api/videos", copyParams(urlParams).set("s", pageSize.toString()))
      console.log("render:" + target)

      doGET(target).then(response => { setResult(response); });
    }, [location]
  )

  useEffect(() => {

      console.log("w: " + size.width + ", h: " + size.height)

    }, [size]
  )

  const cols_calc =  Math.round(size.width / 350);
  const ncols = Math.min(Math.max(2, cols_calc), 4);
  const nrows: number = Math.ceil(result.videos.length / ncols);
  const tdclazz = `grid-${ncols}`

  let rows = [...new Array(nrows)].map((e, y) => {
    let cols = [...new Array(ncols)].map((e, x) => {

      const idx = (y * ncols) + x;

      if (idx <= result.videos.length - 1) {
        const movie: Video = result.videos[idx];
        const link: string = "/video/" + movie.id;
        const duration: number = movie.duration

        return <td className={tdclazz}><Thumbnail src={movie.thumbnail} link={link} title={movie.title} duration={duration}/></td>;
      } else {
        return <td className={tdclazz}></td>;
      }

    });
    return <tbody><tr className="full-width"> {cols} </tr></tbody>;
  });



  return (
    <div className="full-width">
      <table className="gallery">
        {rows}
      </table>
      <GalleryPagination current={current} last={Math.trunc(result.total / pageSize)} />
    </div>
  );
}

function GalleryPagination(props: { current: number, last: number }) {

  console.log("last: " + props.last)

  const location = useLocation();
  const history = useHistory();

  const urlParams = new URLSearchParams(location.search)

  const navigate = (n: number) => {
    const targetParams = copyParams(urlParams).set("p", n.toString())
    const target = buildUrl("/search", targetParams)
    history.push(target);
  };

  let items = [<Pagination.Item active>{props.current}</Pagination.Item>]

  const itemPagination = (n: number) => {
    return <Pagination.Item onClick={() => navigate(n)}>{n}</Pagination.Item>
  }

  if (props.current > 1)
    items.unshift(itemPagination(props.current - 1 ))
  if (props.current > 2)
    items.unshift(<Pagination.Ellipsis />)
  if (props.current < props.last - 1)
    items.push(itemPagination(props.current + 1 ))
  if (props.current < props.last - 2)
    items.push(<Pagination.Ellipsis />)

  return (
    <Pagination className="searchPagination">
      <Pagination.First onClick={ () => navigate(1) } />
      <Pagination.Prev onClick={ () => navigate(Math.max(props.current -1, 1)) } />
      {
        items
      }
      <Pagination.Next onClick={ () => navigate(Math.min(props.current + 1, props.last)) }/>
      <Pagination.Last onClick= { () => navigate(props.last) } />
    </Pagination>
  );
}

export default Gallery;
