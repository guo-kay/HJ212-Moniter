package cn.zqgx.moniter.center.hj212.format.segment.core;


import cn.zqgx.moniter.center.hj212.format.segment.base.cfger.Configurator;
import cn.zqgx.moniter.center.hj212.format.segment.base.cfger.Configured;
import cn.zqgx.moniter.center.hj212.stream.reader.core.ReaderStream;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicReference;

import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.END_ENTRY;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.END_KEY;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.END_OBJECT_VALUE;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.END_PART_KEY;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.END_SUB_ENTRY;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.NOT_AVAILABLE;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.NULL_VALUE;
import static cn.zqgx.moniter.center.hj212.format.segment.core.SegmentToken.START_OBJECT_VALUE;
import static cn.zqgx.moniter.center.hj212.format.segment.core.feature.SegmentParserFeature.ALLOW_ISOLATED_KEY;
import static cn.zqgx.moniter.center.hj212.format.segment.core.feature.SegmentParserFeature.ALLOW_KEY_NOT_CLOSED;
import static cn.zqgx.moniter.center.hj212.format.segment.core.feature.SegmentParserFeature.IGNORE_INVAILD_SYMBOL;

/**
 * Created by xiaoyao9184 on 2018/1/3.
 */
@SuppressWarnings("incomplete-switch")
public class SegmentParser
        implements Closeable, Configured<SegmentParser> {

//    private ParseContext context;

    protected PushbackReader reader;
    private int parserFeature;
    private SegmentToken currentToken;
    private Stack<String> path;

    public SegmentParser(Reader reader){
        this.reader = new PushbackReader(reader,3);
        this.path = new Stack<>();
    }

    //TODO config


//    public SegmentParser(PackParser packParser) throws IOException, SegmentFormatException {
//        super(null);
//        char[] sc = packParser.readSegmentOnly();
//        CharArrayReader reader = new CharArrayReader(sc);
//        super.reader = reader;
//    }

    /**
     * ??????KEY
     * @return KEY
     * @throws IOException
     */
    public String readKey() throws IOException {
        return readPathKey(false);
    }

    /**
     * ???????????????KEY
     * @return ??????KEY
     * @throws IOException
     */
    public String readPathKey() throws IOException {
        return readPathKey(true);
    }

    /**
     * ??????KEY
     * @param supportSubKey true????????????-?????????
     * @return KEY
     * @throws IOException
     */
	private String readPathKey(boolean supportSubKey) throws IOException {
        //??????Token
        switch (currentToken){
            case END_KEY:
                throw new IOException("Cant read key after END_KEY token!");
            case END_OBJECT_VALUE:
                //???&&???????????????????????????,??????;??????????????????????????????????????????
                // ?????????????????????????????????KEY
                ReaderStream.of(reader)
                        .next()
                            .when(END_SUB_ENTRY::isSame)
                            .then(() -> currentToken = END_SUB_ENTRY)
                            .when(END_ENTRY::isSame)
                            .then(() -> currentToken = END_ENTRY)
                            .done()
                        .match();
                break;
        }

        //????????????????????????,????????????????????????????????????
        CharBuffer buffer = CharBuffer.allocate(10+2);

        //?????????Token
        int len = ReaderStream.of(reader)
                .next()
                    .when(NOT_AVAILABLE::isSame)
                    .then(() -> currentToken = NOT_AVAILABLE)

                    //perfect
                    .when(c -> supportSubKey && END_PART_KEY.isSame(c))
                    .then(() -> currentToken = END_PART_KEY)
                    .when(END_KEY::isSame)
                    .then()
                        .next(2)
                            .when(START_OBJECT_VALUE::isSame)
                            .then(() -> currentToken = START_OBJECT_VALUE)
                            .done()
                        .back()
                    .then(() -> currentToken = END_KEY)

                    .when(END_SUB_ENTRY::isSame)
                    .then(() -> {
                        //Missing '=' core value
                        if(!ALLOW_ISOLATED_KEY.enabledIn(parserFeature)){
                            throw new IOException("Missing '=' between key and (null)value");
                        }
                        //NULL Value
                        currentToken = SegmentToken.END_SUB_ENTRY;
                    })
                    .when(END_ENTRY::isSame)
                    .then(() -> {
                        //Missing '=' core value
                        if(!ALLOW_ISOLATED_KEY.enabledIn(parserFeature)){
                            throw new IOException("Missing '=' between key and (null)value");
                        }
                        //NULL Value
                        currentToken = SegmentToken.END_ENTRY;
                    })

                    //key&&
                    //???????????????= START_OBJECT_VALUE
                    //?????????NullValue END_OBJECT_VALUE
                    .when(START_OBJECT_VALUE::isStart)
                    .then()
                        .next()
                            .when(START_OBJECT_VALUE::isStart)
                            .then(() -> {
                                if(path.empty()){
                                    //Missing '='
                                    if(!ALLOW_KEY_NOT_CLOSED.enabledIn(parserFeature)){
                                        throw new IOException("Missing '=' between key and (object)value");
                                    }
                                    //Object Value
                                    currentToken = SegmentToken.START_OBJECT_VALUE;
                                }else{
                                    //NULL Value
                                    currentToken = SegmentToken.END_OBJECT_VALUE;
                                }
                            })
                            .done()
                        .back()
                    .then(() -> {
                        if(!IGNORE_INVAILD_SYMBOL.enabledIn(parserFeature)){
                            throw new IOException("Invaild symbol '&' in key");
                        }
                        //Ignore Invaild symbol
                    })
                    .done()
                .read(buffer);

        if(currentToken == END_OBJECT_VALUE){
            path.pop();
        }

        if(len == 0){
            return null;
        }

        buffer.rewind();
        String result = buffer.toString().substring(0,len);
        if(currentToken == START_OBJECT_VALUE){
            path.push(result);
        }
        return result;

//        mode = SegmentMode.KEY;
//        StringWriter sw = new StringWriter();
//        int i;
//        while((i = reader.next()) != -1) {
//            char c = (char)i;
//
//            if(supportSubKey &&
//                    END_PART_KEY.isSame(c)){
//                //End of Main Key
//                currentToken = END_PART_KEY;
//                break;
//            }else if(SegmentToken.END_KEY.isSame(c)){
//                //End of Key
//                reader.mark(-0);
//                char[] c2 = new char[2];
//                reader.next(c2);
//                if(SegmentToken.START_OBJECT_VALUE.isSame(c2)){
//                    currentToken = SegmentToken.START_OBJECT_VALUE;
//                    break;
//                }
//                reader.reset();
//                currentToken = SegmentToken.END_KEY;
//                break;
//            }else if(SegmentToken.END_SUB_ENTRY.isStart(c)){
//                //Missing '=' core value
//                if(!ALLOW_ISOLATED_KEY.enabledIn(parserFeature)){
//
//                }
//                //NULL Value
//                currentToken = SegmentToken.END_SUB_ENTRY;
//                break;
//            }else if(SegmentToken.END_ENTRY.isStart(c)){
//                //Missing '=' core value
//                if(!ALLOW_ISOLATED_KEY.enabledIn(parserFeature)){
//
//                }
//                //NULL Value
//                currentToken = SegmentToken.END_ENTRY;
//                break;
//            }else if(SegmentToken.END_OBJECT_VALUE.isStart(c)){
//                int finalI = i;
//                if(readIf(nc -> nc == finalI)){
//                    //Missing '='
//                    if(!ALLOW_KEY_NOT_CLOSED.enabledIn(parserFeature)){
//
//                    }
//                    //Object Value
//                    currentToken = SegmentToken.START_OBJECT_VALUE;
//                    break;
//                }
////                reader.mark(-0);
////                if(reader.next() == i){
////                    //Missing '='
////                    if(!ALLOW_KEY_NOT_CLOSED.enabledIn(parserFeature)){
////
////                    }
////                    //Object Value
////                    currentToken = SegmentToken.START_OBJECT_VALUE;
////                    break;
////                }
////                reader.reset();
//
//                if(!IGNORE_INVAILD_SYMBOL.enabledIn(parserFeature)){
//
//                }
//                //Ignore Invaild symbol
//                continue;
//            }
//            sw.append(c);
//        }
//        return sw.toString();
    }

    /**
     * ??????VALUE
     * @return VALUE
     * @throws IOException
     */
    public String readValue() throws IOException {
        return readValue(false);
    }

    /**
     * ????????????VALUE
     * @return ??????VALUE
     * @throws IOException
     */
    public String readObjectValue() throws IOException {
        return readValue(true);
    }


    /**
     * ??????VALUE
     * @param onlyObject true???????????????&&?????????
     * @return VALUE
     * @throws IOException
     */
    private String readValue(boolean onlyObject) throws IOException {
        //????????????????????????Value
        boolean basic = !onlyObject;
        //??????Token
        switch (currentToken){
            case END_ENTRY:
            case END_SUB_ENTRY:
            case END_PART_KEY:
            case END_OBJECT_VALUE:
                throw new IOException("Cant read value after " + currentToken.name() + " token!");
            case START_OBJECT_VALUE:
                if(onlyObject){
                    //??????????????????Object
                    basic = false;
                }else{
                    throw new IOException("Cant read base value after " + currentToken.name() + " token!");
                }
                break;
        }

        AtomicReference<Integer> deep = new AtomicReference<>(0);
        // @formatter:off
        CharBuffer buffer = CharBuffer.allocate(basic ? 50 : 1024);
        boolean finalBasic = basic;
        int len = ReaderStream.of(reader)
                .next()
                    .when(NOT_AVAILABLE::isSame)
                    .then(() -> currentToken = NULL_VALUE)
                    //??????????????????
                    .when(c -> finalBasic && END_SUB_ENTRY.isSame(c))
                    .then(() -> currentToken = END_SUB_ENTRY)//break
                    .when(c -> finalBasic && END_ENTRY.isSame(c))
                    .then(() -> currentToken = END_ENTRY)//break

                    //??????
                    .when(END_KEY::isSame)
                    .then()
                        .next(2)
                            .when(START_OBJECT_VALUE::isSame)
                            .then(() -> {
                                //??????4??????&???
                                deep.getAndSet(deep.get() + 4);
//                                currentToken = START_OBJECT_VALUE;
//                                return Optional.empty();
                            })
                            .back()//??????
                        .back()

//                    .when(c -> currentToken == START_OBJECT_VALUE && START_OBJECT_VALUE.isStart(c))
//                    .then()
//                    .skip()

//                    .when(c -> currentToken != START_OBJECT_VALUE && END_OBJECT_VALUE.isStart(c))

                    //value&&
                    //???????????????object START_OBJECT_VALUE
                    //?????????????????????object END_OBJECT_VALUE
                    //??????????????? END_OBJECT_VALUE
                    .when(c -> START_OBJECT_VALUE.isStart(c))
                    .then()
                        .next()
                            .when(START_OBJECT_VALUE::isStart)
                            .then(() -> {
                                if(deep.get() == 0){
                                    currentToken = END_OBJECT_VALUE;
                                    return Optional.of(true);
                                }else{
//                                    deep.getAndSet(deep.get() - 1);
                                    return Optional.empty();
                                }
                            })
                            .done()
                        .back()
                    .then(() -> {
                        if(deep.get() != 0){
                            //?????????
                            deep.getAndSet(deep.get() - 1);
                            return Optional.empty();
                        }

                        if(!IGNORE_INVAILD_SYMBOL.enabledIn(parserFeature)){

                        }
                        //Ignore Invaild symbol
                        return Optional.empty();
                    })
                    .done()
                .read(buffer);
        // @formatter:on
        if(currentToken == END_OBJECT_VALUE){
            path.pop();
        }

        if(len == 0){
            return null;
        }

        buffer.rewind();
        //???????????????"\0"??????
        String result = buffer.toString().substring(0,len);
        return result;
//
//
//        mode = SegmentMode.VALUE;
//        StringWriter sw = new StringWriter();
//        int i;
//        while((i = reader.next()) != -1) {
//            char c = (char)i;
//            if(SegmentToken.END_SUB_ENTRY.isSame(c)){
//                //End of Value in Sub Entry
//                currentToken = SegmentToken.END_SUB_ENTRY;
//                break;
//            }else if(SegmentToken.END_ENTRY.isSame(c)) {
//                //End of Value in Entry
//                currentToken = SegmentToken.END_ENTRY;
//                break;
//            }else if(SegmentToken.END_OBJECT_VALUE.isStart(c)){
//                int finalI = i;
//                if(readIf(nc -> nc == finalI)){
//                    //End of Value in Object
//                    currentToken = SegmentToken.END_OBJECT_VALUE;
//                    break;
//                }
////                reader.mark(-0);
////                if(reader.next() == i){
////                    //End of Value in Object
////                    currentToken = SegmentToken.END_OBJECT_VALUE;
////                    break;
////                }
////                reader.reset();
//
//                if(!IGNORE_INVAILD_SYMBOL.enabledIn(parserFeature)){
//
//                }
//                //Ignore Invaild symbol
//                continue;
//            }else {
//                reader.mark(-0);
//                char[] c2 = new char[2];
//                reader.next(c2);
//                if(SegmentToken.END_OBJECT_VALUE.isSame(c2)){
//                    currentToken = SegmentToken.START_OBJECT_VALUE;
//                }else{
//                    reader.reset();
//                    currentToken = SegmentToken.START_VALUE;
//                }
//
//
//            }
//            sw.append(c);
//        }
//        return sw.toString();
    }


    /**
     * ??????Token
     * @return SegmentToken
     */
    public SegmentToken currentToken(){
        return currentToken;
    }

    public void initToken(){
        currentToken = START_OBJECT_VALUE;
    }

    public void setParserFeature(int parserFeature) {
        this.parserFeature = parserFeature;
    }

    @Override
    public void close(){
        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void configured(Configurator<SegmentParser> by) {
        by.config(this);
    }

}
