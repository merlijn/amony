import Dropdown from "react-bootstrap/Dropdown";
import React, {MouseEventHandler, ReactNode} from "react";
import Button from "react-bootstrap/Button";
import './TripleDotMenu.scss';
import {SelectCallback} from "react-bootstrap/helpers";
import ImgWithAlt from "./ImgWithAlt";

type ToggleProps = { children: React.ReactNode; onClick: MouseEventHandler<HTMLElement>, className?: string };
type Props = { children: React.ReactNode; className: string };

const TripleDotToggle =
  React.forwardRef<Button, ToggleProps>((props, ref) => (
    <div
      id="config-button"
      className={`triple-dot-toggle ${props.className}`}
      onClick={(e) => {
        e.preventDefault()
        props.onClick(e);
      }}>
      <ImgWithAlt className="action-icon-small" src="/more_vert_black_24dp.svg" />
      {props.children}
    </div>
));

const TripleDotDropdown = React.forwardRef<HTMLDivElement, Props>((props, ref) => {
    return (
      <div ref={ref} className={`triple-dot-menu ${props.className}`}>
        {props.children}
      </div>
    );
  },
);

interface TripleDotMenuProps {
  className?: string
  children?: ReactNode
  onSelect?: SelectCallback
}

const TripleDotMenu = (props: TripleDotMenuProps) => {

  return(
    <Dropdown onSelect = { props.onSelect } id="dropdown-menu-align-right">
      <Dropdown.Toggle className={props.className} as={TripleDotToggle}></Dropdown.Toggle>
      <Dropdown.Menu align="right" as={TripleDotDropdown}>
        { props.children }
      </Dropdown.Menu>
    </Dropdown>
  );
}

export default TripleDotMenu