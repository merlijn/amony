import Dropdown from "react-bootstrap/Dropdown";
import React, {MouseEventHandler, useState} from "react";
import Button from "react-bootstrap/Button";
import './TripleDotMenu.scss';

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
      <img className="action-icon-small" src="/more_vert_black_24dp.svg" />
      {props.children}
    </div>
));

const TripleDotDropdown = React.forwardRef<HTMLDivElement, Props>((props, ref) => {
    const [value, setValue] = useState('');

    return (
      <div ref={ref} className={`triple-dot-menu ${props.className}`}>
        {props.children}
      </div>
    );
  },
);

const TripleDotMenu = (props: { className?: string }) => {

  return(
    <Dropdown id="dropdown-menu-align-right">
      <Dropdown.Toggle className={props.className} as={TripleDotToggle}></Dropdown.Toggle>

      <Dropdown.Menu align="right" as={TripleDotDropdown}>
        <Dropdown.Item className="menu-item" eventKey="1"><img className="menu-icon" src="/info_black_24dp.svg" />Info</Dropdown.Item>
        <Dropdown.Item className="menu-item" eventKey="2"><img className="menu-icon" src="/delete_black_24dp.svg" />Delete</Dropdown.Item>
      </Dropdown.Menu>
    </Dropdown>
  );
}

export default TripleDotMenu