import Dropdown from "react-bootstrap/Dropdown";
import React, {MouseEventHandler, useEffect, useState} from "react";
import Button from "react-bootstrap/Button";
import Image from 'react-bootstrap/Image';
import './ConfigMenu.scss';
import Form from "react-bootstrap/Form";
import {useCookiePrefs} from "../api/Util";
import {defaultPrefs, Prefs} from "../api/Model";

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
    return (
      <div ref={ref} className={`config-menu ${props.className}`}>
        {props.children}
      </div>
    );
  },
);



const ConfigMenu = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)

  return(
    <Dropdown>
      <Dropdown.Toggle as={CustomToggle} id="dropdown-custom-components"></Dropdown.Toggle>

      <Dropdown.Menu as={CustomMenu}>
        <div className="justify-content-center">
            <Form.Check
              type="checkbox"
              label="Show video titles"
              checked={ prefs.showTitles }
              onChange={(e) => {
                  setPrefs( { showTitles: !prefs.showTitles })
                }
              }
            />
        </div>
      </Dropdown.Menu>
    </Dropdown>
  );
}

export default ConfigMenu