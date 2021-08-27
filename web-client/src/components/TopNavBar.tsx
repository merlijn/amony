import React, {useEffect, useState} from "react";
import {useHistory, useLocation} from "react-router-dom";
import {buildUrl, copyParams, useCookiePrefs} from "../api/Util";
import Navbar from "react-bootstrap/Navbar";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import './TopNavBar.scss';
import {Api} from "../api/Api";
import {defaultPrefs, Prefs, Tag} from "../api/Model";
import {ButtonGroup} from "react-bootstrap";
import ConfigMenu from "./ConfigMenu";
import ImgWithAlt from "./shared/ImgWithAlt";
import DropDownIcon from "./shared/DropDownIcon";
import {Constants} from "../api/Constants";
import Button from "react-bootstrap/Button";
import Dropdown from "react-bootstrap/Dropdown";

function TopNavBar() {

  const location = useLocation();
  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", defaultPrefs)
  const [query, setQuery] = useState("")
  const [tags, setTags] = useState<Array<Tag>>([]);
  const [selectedTag, setSelectedTag] = useState<Tag>({id: 0, title: ""})

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
  };

  useEffect(() => {
    const params = copyParams(new URLSearchParams(location.search))
    const tagId = params.get("tag")

    if (tagId) {
      const found = tags.find((e) => e.id.toString() === tagId)
      if (found)
        setSelectedTag(found)
    }

    setQuery(params.get("q") || "")
  }, [location, tags]);

  // fetch tags
  useEffect(() => { Api.getTags().then(response => { setTags(response) }); }, []);

  const queryChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
  };

  const clearQuery = () => {
    document.getElementById("nav-search-input")?.focus()
    setQuery("")
  }

  // fixed="top"
  return(
    <Navbar className="TopNavBar" fixed="top">
        <div key="nav-bar-left" className="bar-left">

        </div>
        <div key="nav-bar-center" className="bar-center">

          <Dropdown className="tag-list-dropdown mr-sm-1" key="nav-tag-list" as={ButtonGroup} size="sm">
            <Button size="sm" className="home-button"><a href="/">
              <ImgWithAlt src="/home_black_24dp.svg" />
            </a></Button>

            <Dropdown.Toggle className="tag-list-toggle" split id="dropdown-split-basic" />

            <Dropdown.Menu>
              {
                tags.map((t) => {
                  return <NavDropdown.Item key={`nav-tag-${t.id}`} href={`/search?tag=${t.id}`}>{t.title}</NavDropdown.Item>
                })
              }
            </Dropdown.Menu>
          </Dropdown>

          <Form className="nav-search-form" onSubmit={doSearch} inline>
            <div key="nav-search-input" className="nav-search-input mr-sm-1">
              <FormControl id="nav-search-input" size="sm" type="text" placeholder="Search" value={query} onChange={queryChanged} />
              <ImgWithAlt className="nav-clear-input action-icon-small" src="/clear_input.svg"
                          onClick={(e) => clearQuery() } />
            </div>
          </Form>

          <DropDownIcon iconSrc="/tune_black_24dp.svg" alignRight={true} contentClassName="filter-menu" buttonClassName="filter-menu-button mr-sm-1">
            <>
              <div className="filter-menu-title">Filters</div>

              <div className="filter-menu-form">
                <div style={{float:"left"}} className="mr-sm-2">Video quality</div>
                {
                  Constants.resolutions.map((v) => {
                    return <Form.Check
                      style={ { float:"left" } }
                      className="mr-sm-1"
                      name="ncols"
                      type="radio"
                      value={v.value}
                      label={v.label}
                      checked={prefs.minRes === v.value}
                      onChange={(e) => { setPrefs( { ...prefs, minRes: v.value }) }
                      } />;
                  })
                }
              </div>
            </>
          </DropDownIcon>

          <ConfigMenu key="nav-config-menu"/>
        </div>
        <div key="nav-bar-right" className="bar-right"></div>
    </Navbar>
  );
}

export default TopNavBar