import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams} from "../api/Util";
import Pagination from "react-bootstrap/Pagination";
import React, {useEffect} from "react";

type Props = {
  className?: string,
  current: number,
  last: number
}

const GalleryPagination: React.FC<Props> = (props) => {

  const location = useLocation();
  const urlParams = new URLSearchParams(location.search)
  const history = useHistory();

  const navigate = (n: number) => {
    const targetParams = copyParams(urlParams).set("p", n.toString())
    const target = buildUrl("/search", targetParams)
    history.push(target);
    window.scrollTo({
      top: 0,
      behavior: 'auto'
    });
  };

  const propsRef = React.useRef(props);

  useEffect(() => {
      propsRef.current = props
    }, [props]
  )

  useEffect( () => {

    document.addEventListener('keydown', (event: KeyboardEvent) => {

      const p = propsRef.current

      if (event.code === 'ArrowRight' && p.current < p.last) {
        navigate(p.current + 1)
      } else if (event.code === 'ArrowLeft' && p.current > 1) {
        navigate(p.current - 1)
      }
    })
  }, [])

  let items = []

  let clazz = `searchPagination ${props.className}`

  return (
      <Pagination size="sm" className={clazz}>
        <Pagination.First key="nav-first" onClick={ () => navigate(1) } />
        <Pagination.Prev key="nav-prev" onClick={ () => navigate(Math.max(props.current -1, 1)) } />
        <Pagination.Item key="nav-current" style={ { width: 30, textAlign: "center" } }active>{props.current}</Pagination.Item>
        <Pagination.Next key="nav-next" onClick={ () => navigate(Math.min(props.current + 1, props.last)) }/>
        <Pagination.Last key="nav-last" onClick= { () => navigate(props.last) } />
      </Pagination>
  );
}

export default GalleryPagination