import {Children, CSSProperties, ReactNode, useRef, useState} from "react";
import "./DropDown.scss";
import {useListener} from "../../api/ReactUtils";
import React from "react";

export type DropDownProps = {
  toggleClassName?: string
  contentClassName?: string
  children?: ReactNode,
  toggleIcon?: ReactNode,
  toggleLabel?: string,
  showArrow?: boolean, 
  hideOnClick: boolean,
  align?: 'left' | 'right'
  onToggle?: (visible: boolean) => void
}

export const DropDown = (props: DropDownProps ) => {

  const [isOpen, setIsOpen] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLDivElement>(null)

  const toggle = 
    <>
      { props.toggleLabel && <span className="my-dropdown-label">{props.toggleLabel}</span> }
      { props.toggleIcon }
      { props.showArrow && <span className="my-dropdown-arrow">{isOpen ? "\u25B2" : "\u25BC"}</span> }
    </>

  //DropDown toggler
  const setShowDropDown = (value: boolean) => {
    setIsOpen(value)
    props.onToggle && props.onToggle(value);
  };

  const handleClickOutside = (event: MouseEvent) => {
    const path = event.composedPath && event.composedPath();

    if (path) {
      if (contentRef?.current && !path.includes(contentRef.current) &&
          toggleRef?.current && !path.includes(toggleRef.current)) 
        setShowDropDown(false)
    }
  };

  useListener('mousedown', handleClickOutside)

  const alignStyle = props.align === 'right' ? { right: 0 } : { left: 0 }

  return <div className="my-dropdown-container">
    <div
      className = { "my-dropdown-toggle " + (props.toggleClassName ? props.toggleClassName : "") }
      onClick = { () => setShowDropDown(!isOpen) }
      ref = { toggleRef }>
      { toggle }
    </div>
    <div className="my-dropdown-content-container">
      {
        isOpen && (
        <div style = { alignStyle } className = { "my-dropdown-content " + (props.contentClassName ? props.contentClassName : "") } ref = { contentRef }>
          {
            // children
            React.Children.map(props.children, child => {
              if (!React.isValidElement(child)) {
                return child;
              }

              return React.cloneElement(child, { internalOnParentClick: () => {
                  if (props.hideOnClick) {
                    setShowDropDown(false)
                  }
              } });
            })
          }
        </div>
      )}
    </div>
  </div>
}

export const Menu = (props: {children?: ReactNode, style?: CSSProperties, onParentClick?: () => any})=> {
  return <div style = { props.style } className="my-dropdown-menu"> { props.children } </div>
}

export const MenuItem = (props: { className?: string, children?: ReactNode, href?: string, onClick?: () => any, internalOnParentClick?: () => any }) => {

  const item = 
      <div 
        className= { "my-dropdown-menu-item "  + (props.className ? props.className : "") } 
        onClick = { (e) => { 
          props.onClick && props.onClick(); 
          props.internalOnParentClick && props.internalOnParentClick() 
        }}>
        {
          props.children
        }
      </div>

  return props.href ? <a href={props.href}>{item}</a> : item
}
