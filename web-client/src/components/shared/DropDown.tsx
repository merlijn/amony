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

  const [showDropdown, setShowDropdown] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLDivElement>(null)

  const toggle = 
    <>
      { props.toggleLabel && <span className = "dropdown-label">{ props.toggleLabel }</span> }
      { props.toggleIcon }
      { props.showArrow && <span className = "dropdown-arrow">{ showDropdown ? "\u25B2" : "\u25BC"}</span> }
    </>

  //DropDown toggler
  const setShowDropDownFn = (value: boolean) => {
    setShowDropdown(value)
    props.onToggle && props.onToggle(value);
  };

  const handleClickOutside = (event: MouseEvent) => {
    const path = event.composedPath && event.composedPath();

    if (path) {
      if (contentRef?.current && !path.includes(contentRef.current) &&
          toggleRef?.current && !path.includes(toggleRef.current)) 
        setShowDropDownFn(false)
    }
  };

  useListener('mousedown', handleClickOutside)

  const alignStyle = props.align === 'right' ? { right: 0 } : { left: 0 }

  return <div className="dropdown-container">
    <div
      className = { "dropdown-toggle " + (props.toggleClassName ? props.toggleClassName : "") }
      onClick = { () => setShowDropDownFn(!showDropdown) }
      ref = { toggleRef }>
      { toggle }
    </div>
    <div className="dropdown-content-container">
      {
        showDropdown && (
        <div style = { alignStyle } 
             className = { "dropdown-content " + (props.contentClassName ? props.contentClassName : "")  } 
             ref = { contentRef }>
          {
            // children
            React.Children.map(props.children, child => {
              if (!React.isValidElement(child)) {
                return child;
              }

              return React.cloneElement(child, { internalOnParentClick: () => {
                  if (props.hideOnClick) {
                    setShowDropDownFn(false)
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
  return <div style = { props.style } className="dropdown-menu"> { props.children } </div>
}

export const MenuItem = (props: { className?: string, children?: ReactNode, href?: string, onClick?: () => any, internalOnParentClick?: () => any }) => {

  const item = 
      <div 
        className= { "dropdown-menu-item "  + (props.className ? props.className : "") } 
        onClick = { (e) => { 
          props.internalOnParentClick && props.internalOnParentClick();
          props.onClick && props.onClick(); 
        }}>

        {
          props.children
        }

      </div>

  return props.href ? <a href={props.href}>{item}</a> : item
}
