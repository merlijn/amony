import React, { CSSProperties, ReactNode } from "react"
import { useListener } from "../../api/ReactUtils"

const defaultFetchMargin = 1024;

export type InfiniteScrollProps = {
  style?: CSSProperties
  className?: string
  fetchContent?: () => any
  scrollType: 'page' | 'element'
  fetchMargin?: number
  children: ReactNode
}

const Scrollable = React.forwardRef<HTMLDivElement,InfiniteScrollProps>((props, ref) => {

  const fetchMargin = props.fetchMargin !== undefined ? props.fetchMargin : defaultFetchMargin;

  const onPageScroll = (e: Event) => {

    const withinFetchMargin = 
        document.documentElement.offsetHeight - Math.ceil(window.innerHeight + document.documentElement.scrollTop) <=  fetchMargin

    if (props.scrollType === 'page' && withinFetchMargin && props.fetchContent)
      props.fetchContent()
  }
  
  useListener('scroll', onPageScroll)
  
  const onElementScroll = (e: React.UIEvent<HTMLDivElement, UIEvent>) => { 

    const withinFetchMargin = 
      (e.currentTarget.scrollTop + e.currentTarget.clientHeight) >= e.currentTarget.scrollHeight;

    if (props.scrollType === 'element' && withinFetchMargin && props.fetchContent)
      props.fetchContent()
  }

  return (
    <div style={ props.style } className={props.className} ref = {ref} onScroll = { onElementScroll }>
      { props.children }
    </div>
  );
})

export default Scrollable