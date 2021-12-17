import React, { CSSProperties, ReactNode } from "react"
import { useListener } from "../../api/ReactUtils"

const fetchDataScreenMargin = 1024;

export type InfiniteScrollProps = {
  style?: CSSProperties,
  className?: string,
  onEndReached?: () => any,
  scroll: 'page' | 'element'
  children: ReactNode
}

const InfiniteScroll = React.forwardRef<HTMLDivElement,InfiniteScrollProps>((props, ref) => {

  const onPageScroll = (e: Event) => {

    const withinFetchMargin = 
        document.documentElement.offsetHeight - Math.ceil(window.innerHeight + document.documentElement.scrollTop) <=  fetchDataScreenMargin

    if (props.scroll === 'page' && withinFetchMargin && props.onEndReached)
      props.onEndReached()
  }
  
  useListener('scroll', onPageScroll)
  
  const onElementScroll = (e: React.UIEvent<HTMLDivElement, UIEvent>) => { 

    const withinFetchMargin = 
      (e.currentTarget.scrollTop + e.currentTarget.clientHeight) >= e.currentTarget.scrollHeight;

    if (props.scroll === 'element' && withinFetchMargin && props.onEndReached)
      props.onEndReached()
  }

  return (
    <div style={ props.style } className={props.className} ref = {ref} onScroll = { onElementScroll }>
      { props.children }
    </div>
  );
})

export default InfiniteScroll