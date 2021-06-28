import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams} from "../api/Util";
import Pagination from "react-bootstrap/Pagination";
import React from "react";

const GalleryPagination = (props: { current: number, last: number }) => {

  const location = useLocation();
  const urlParams = new URLSearchParams(location.search)
  const history = useHistory();

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

export default GalleryPagination