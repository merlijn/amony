import Dropdown from "react-bootstrap/Dropdown";
import React, {CSSProperties, MouseEventHandler, useState} from "react";
import Button from "react-bootstrap/Button";
import Image from 'react-bootstrap/Image';
import './ConfigMenu.scss';
import Form from "react-bootstrap/Form";
import {Col, Row} from "react-bootstrap";

type ToggleProps = { children: React.ReactNode; onClick: MouseEventHandler<HTMLElement> };
type Props = { children: React.ReactNode; className: string };

const CustomToggle = React.forwardRef<Button, ToggleProps>((props, ref) => (
  <Button
    id="config-button"
    className="mr-sm-1 config-menu-button"
    onClick={(e) => {
      e.preventDefault();
      props.onClick(e);
    }}
    size="sm">
    <Image width="25px" height="25px" src="/settings_black_24dp.svg" />
    {props.children}
  </Button>
));

const CustomMenu = React.forwardRef<HTMLDivElement, Props>((props, ref) => {
    const [value, setValue] = useState('');

    return (
      <div ref={ref} className={`config-menu ${props.className}`}>
        {props.children}
      </div>
    );
  },
);

const ConfigMenu = () => {

  return(
    <Dropdown>
      <Dropdown.Toggle as={CustomToggle} id="dropdown-custom-components"></Dropdown.Toggle>

      <Dropdown.Menu as={CustomMenu}>
        <Form className="justify-content-center" inline>
          <Form.Group as={Row} className="mb-3" controlId="formPlaintextEmail">
            <Form.Check
              type="checkbox"
              label="Show video titles"
            />
          </Form.Group>
        </Form>
      </Dropdown.Menu>
    </Dropdown>
  );
}

export default ConfigMenu