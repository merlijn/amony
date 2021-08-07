import Dropdown from "react-bootstrap/Dropdown";
import React, {MouseEventHandler} from "react";
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

  const columns = [1, 2, 3, 4, 5, 6, 7]

  return(
    <Dropdown>
      <Dropdown.Toggle as={CustomToggle} id="dropdown-custom-components"></Dropdown.Toggle>

      <Dropdown.Menu as={CustomMenu}>
        <div className="ml-sm-2">

            <Form.Group>
              <Form.Label className="mr-sm-2">Show video titles:</Form.Label>
              <Form.Check
                type="checkbox"
                style={{float: "left"}}
                label="Show video titles"
                checked={ prefs.showTitles }
                onChange={(e) => {
                    setPrefs( { ...prefs, showTitles: !prefs.showTitles })
                  }
                }
              />
            </Form.Group>

            <Form.Group>
              <Form.Label className="mr-sm-2">Number of columns:</Form.Label>
              <Form.Check
                className="mr-sm-1"
                name="ncols"
                type="radio"
                value={0}
                label="auto"
                checked={prefs.gallery_columns === 0}
                onChange={(e) => {
                    setPrefs( { ...prefs, gallery_columns: 0 })
                  }
                } />
              {
                columns.map((v) => {
                  return <Form.Check
                    className="mr-sm-1"
                    name="ncols"
                    type="radio"
                    value={v}
                    label={v}
                    checked={prefs.gallery_columns === v}
                    onChange={(e) => {
                        setPrefs( { ...prefs, gallery_columns: v })
                      }
                    } />;
                })
              }
            </Form.Group>
        </div>
      </Dropdown.Menu>
    </Dropdown>
  );
}

export default ConfigMenu