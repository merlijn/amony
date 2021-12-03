import {Children, ReactNode, useRef, useState} from "react";
import "./DropDown.scss";
import {useListener} from "../../api/ReactUtils";
import React from "react";

export type DropDownProps = {
  toggleClassName?: string
  contentClassName?: string
  children?: ReactNode,
  label: string, 
  showArrow: boolean, 
  hideOnClick: boolean,
  onToggle?: (visible: boolean) => void
}

export const DropDown = (props: DropDownProps ) => {

  const [isOpen,setIsOpen] = useState(false)
  const contentRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLDivElement>(null)

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

  return <div className="my-dropdown-container">
    <div
      className = { "my-dropdown-toggle " + (props.toggleClassName ? props.toggleClassName : "") }
      onClick = { () => setShowDropDown(!isOpen) }
      ref = { toggleRef }>
      <span className="my-dropdown-label">{props.label}</span>
      { props.showArrow && <span className="my-dropdown-arrow">{isOpen ? "\u25B2" : "\u25BC"}</span> }
    </div>
    <div className="my-dropdown-content-container">
      {
        isOpen && (
        <div className = { "my-dropdown-children " + (props.contentClassName ? props.contentClassName : "") } ref = { contentRef }>
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

export const Menu = (props: {children?: ReactNode, onParentClick?: () => any})=> {
  return <div className="my-dropdown-menu"> { props.children } </div>
}

export const MenuItem = (props: { className?: string, children?: ReactNode, onClick?: () => any, internalOnParentClick?: () => any }) => {

  return <div 
            className= { "my-dropdown-menu-item "  + (props.className ? props.className : "") } 
            onClick = { (e) => { 
              props.onClick && props.onClick(); 
              props.internalOnParentClick && props.internalOnParentClick() 
            }}>
            { props.children }
          </div>
}
